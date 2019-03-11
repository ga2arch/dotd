(ns dotd.core
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as async]
            [clojure.core.cache :as cache])
  (:import (java.nio.channels DatagramChannel)
           (java.net InetSocketAddress)
           (java.nio ByteBuffer ByteOrder)
           (javax.net.ssl SSLSocketFactory SSLSocket)
           (java.io DataOutputStream DataInputStream)
           (net.openhft.hashing LongHashFunction)
           (java.util Date))
  (:gen-class))

(def cloudflare "1.1.1.1")
(def request->response (atom (cache/ttl-cache-factory {} :ttl (* 10 60 1000))))

(defn hash-request
  [^ByteBuffer packet]
  (.rewind packet)
  (.position packet 4)
  (let [h (.hashBytes (LongHashFunction/xx) packet)]
    (.rewind packet)
    h))

(defn start-server
  [port]
  (let [channel (DatagramChannel/open)
        c-send (async/chan 1)
        c-receive (async/chan 1)]
    (-> channel
        (.socket)
        (.bind (InetSocketAddress. port)))
    (log/info "listening on port:" port)
    (async/go-loop []
      (let [request (doto
                      (ByteBuffer/allocate 512)
                      (.order ByteOrder/BIG_ENDIAN))]
        (let [addr (.receive channel request)]
          (.flip request)
          (if-let [response (get @request->response (hash-request request))]
            (async/>! c-send {:addr     addr
                              :request  request
                              :response response})
            (async/>! c-receive {:addr    addr
                                 :request request}))))
      (recur))
    (async/go-loop []
      (let [{:keys [addr request response]} (async/<! c-send)]
        (.rewind request)
        (doto response
          (.rewind)
          (.putShort (.getShort request))
          (.rewind))
        (.send channel response addr)
        (recur)))
    {:c-send    c-send
     :c-receive c-receive
     :channel   channel}))

(defn connect
  [provider]
  (let [ssl-factory (SSLSocketFactory/getDefault)
        ^SSLSocket socket (.createSocket ssl-factory provider 853)]
    (doto socket
      (.setUseClientMode true)
      (.setKeepAlive true)
      (.setTcpNoDelay true)
      (.startHandshake))
    {:in     (DataInputStream. (.getInputStream socket))
     :out    (DataOutputStream. (.getOutputStream socket))
     :socket socket}))

(defn close
  [{:keys [socket in out]}]
  (.close in)
  (.close out)
  (.close socket))

(defn resolve-dns
  [{:keys [in out]} {:keys [addr ^ByteBuffer request]}]
  (let [len (.remaining request)]
    (doto out
      (.writeShort len)
      (.write (.array request))
      (.flush))
    (let [buff (byte-array 512)]
      (.skipBytes in 2)
      (let [n (.read in buff)]
        (when (pos? n)
          (let [response (doto
                           (ByteBuffer/wrap buff)
                           (.order ByteOrder/BIG_ENDIAN)
                           (.position n)
                           (.flip))]
            (swap! request->response assoc (hash-request request) response)
            {:request  request
             :response response
             :addr     addr}))))))

(defn dispatch
  [provider req]
  (let [conn (connect provider)]
    (try
      (resolve-dns conn req)
      (catch Exception e
        (log/error "error resolving dns for req: " (String. (.array req)) e))
      (finally
        (close conn)))))

(defn -main
  []
  (let [{:keys [c-send c-receive channel]} (start-server 53)]
    (def c channel)
    (async/go-loop []
      (let [req (async/<! c-receive)]
        (async/go
          (if-let [resp (dispatch cloudflare req)]
            (async/>! c-send resp)
            (async/>! c-receive req))))
      (recur))
    (async/<!! (async/chan 1))))
