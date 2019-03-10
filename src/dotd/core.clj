(ns dotd.core
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as async]
            [clojure.core.cache :as cache])
  (:import (java.nio.channels DatagramChannel)
           (java.net InetSocketAddress)
           (java.nio ByteBuffer ByteOrder)
           (javax.net.ssl SSLSocketFactory SSLSocket)
           (java.io DataOutputStream DataInputStream)
           (net.openhft.hashing LongHashFunction))
  (:gen-class))

(def cloudflare "1.1.1.1")
(def C1 (atom (cache/ttl-cache-factory {} :ttl 10000)))

(defn hash-packet
  [^ByteBuffer packet]
  (.rewind packet)
  (.position packet 4)
  (let [h (.hashBytes (LongHashFunction/xx) packet)]
    (.rewind packet)
    h))

(defn start-server
  []
  (let [channel (DatagramChannel/open)
        in (async/chan 1)
        out (async/chan 1)]
    (-> channel
        (.socket)
        (.bind (InetSocketAddress. 53)))
    (log/info "listening on port :53")
    (async/go-loop []
      (let [request (ByteBuffer/allocate 512)]
        (.order request ByteOrder/BIG_ENDIAN)
        (.clear request)
        (let [addr (.receive channel request)]
          (.flip request)
          (if-let [response (get @C1 (hash-packet request))]
            (async/>! in {:addr     addr
                          :request  request
                          :response response})
            (async/>! out {:addr    addr
                           :request request}))))
      (recur))
    (async/go-loop []
      (let [{:keys [addr request response]} (async/<! in)]
        (.rewind request)
        (doto response
          (.rewind)
          (.putShort (.getShort request))
          (.rewind))
        (.send channel response addr)
        (recur)))
    {:in      in
     :out     out
     :channel channel}))

(defn connect-provider
  [provider]
  (let [ssl-factory (SSLSocketFactory/getDefault)
        ^SSLSocket socket (.createSocket ssl-factory provider 853)]
    (.setUseClientMode socket true)
    (.startHandshake socket)
    {:socket socket
     :in     (DataInputStream. (.getInputStream socket))
     :out    (DataOutputStream. (.getOutputStream socket))}))

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
            (swap! C1 assoc (hash-packet request) response)
            {:request  request
             :response response
             :addr     addr}))))))

(defn dispatch
  [provider req]
  (let [{:keys [socket in out] :as conn} (connect-provider provider)]
    (try
      (resolve-dns conn req)
      (finally
        (.close in)
        (.close out)
        (.close socket)))))

(defn -main
  []
  (let [{:keys [in out channel]} (start-server)
        xf (comp
             (map (partial dispatch cloudflare))
             (filter (complement nil?)))]
    (def c channel)
    (async/pipeline-blocking 10 in xf out)
    (async/<!! (async/chan 1))))
