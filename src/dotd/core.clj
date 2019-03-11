(ns dotd.core
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as async]
            [clojure.core.cache :as cache])
  (:import (java.nio.channels DatagramChannel SocketChannel)
           (java.net InetSocketAddress SocketOptions StandardSocketOptions)
           (java.nio ByteBuffer ByteOrder)
           (javax.net.ssl SSLSocketFactory SSLSocket SSLContext)
           (java.io DataOutputStream DataInputStream)
           (net.openhft.hashing LongHashFunction)
           (tlschannel ClientTlsChannel))
  (:gen-class))

(def cloudflare "1.1.1.1")
(def connection (atom nil))
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
  [^String provider]
  (let [^SSLContext ssl-ctx (SSLContext/getDefault)
        ^SocketChannel channel (doto (SocketChannel/open)
                                 (.connect (InetSocketAddress. provider 853)))
        tls-channel (-> (ClientTlsChannel/newBuilder channel ssl-ctx)
                        (.build))]
    (doto channel
      (.setOption StandardSocketOptions/TCP_NODELAY true))
    {:channel tls-channel
     :socket  channel}))

(defn close-connection
  []
  (when-let [conn @connection]
    (do
      (.close (:channel conn))
      (.close (:socket conn))
      (reset! connection nil))))

(defn get-connection
  [^String provider]
  (if-let [conn @connection]
    (if (.isOpen (:channel conn))
      conn
      (reset! connection (connect provider)))
    (reset! connection (connect provider))))

(defn resolve-dns
  [{:keys [^ClientTlsChannel channel]} {:keys [addr ^ByteBuffer request]}]
  (let [len (.remaining request)
        req (doto (ByteBuffer/allocate (+ 2 len))
              (.order ByteOrder/BIG_ENDIAN)
              (.putShort len)
              (.put request)
              (.rewind))
        written (.write channel req)]
    (.rewind request)
    (let [buff (doto
                 (ByteBuffer/allocate 512)
                 (.order ByteOrder/BIG_ENDIAN))]
      (let [n (.read channel buff)]
        (if (= -1 n)
          (close-connection)
          (let [response (doto buff
                           (.flip)
                           (.position 2)
                           (.compact))]
            (swap! request->response assoc (hash-request request) response)
            {:request  request
             :response response
             :addr     addr}))))))

(defn dispatch
  [provider conn req]
  (try
    (resolve-dns conn req)
    (catch Exception e
      (log/error "error resolving dns for req: " req e)
      (close-connection))
    (finally
      ;(close conn)
      )))

(defn -main
  []
  (let [{:keys [c-send c-receive channel]} (start-server 53)]
    (def c channel)
    (async/go-loop []
      (let [req (async/<! c-receive)
            retries (or (:retries (meta req)) 0)]
        (when (< retries 10)
          (let [conn (get-connection cloudflare)]
            (async/go
              (if-let [resp (dispatch cloudflare conn req)]
                (async/>! c-send resp)
                (async/>! c-receive (with-meta req {:retries (inc retries)})))))))
      (recur))
    (async/<!! (async/chan 1))
    ))
