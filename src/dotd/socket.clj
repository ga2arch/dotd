(ns dotd.socket
  (:require [clojure.core.async :as async])
  (:import (java.nio.channels Selector SocketChannel SelectionKey ServerSocketChannel)
           (java.net InetSocketAddress StandardSocketOptions)
           (java.nio ByteBuffer)
           (javax.net.ssl SSLEngine SSLContext SSLEngineResult SSLEngineResult$HandshakeStatus)
           (tlschannel SniSslContextFactory)))

(defn get-result-status
  [result]
  (keyword (.name (.getStatus result))))

(defn get-handshake-status
  [ssl-engine]
  [(keyword (.name (.getHandshakeStatus ssl-engine)))])

(def handshake nil)
(defmulti handshake (fn [_ status] status))

(defmethod handshake [:NEED_WRAP] [{:keys [ssl-engine packet-buffer-dst app-buffer-src] :as state} status]
  (println status)
  (let [^SSLEngineResult result (.wrap ssl-engine app-buffer-src packet-buffer-dst)]
    [state [:NEED_WRAP (get-result-status result)]]))

(defmethod handshake [:NEED_WRAP :OK] [{:keys [ssl-engine channel app-buffer-src packet-buffer-dst] :as state} status]
  (println status)
  (.clear app-buffer-src)
  (.flip packet-buffer-dst)
  (while (.hasRemaining packet-buffer-dst)
    (.write channel packet-buffer-dst))
  (.compact packet-buffer-dst)
  [state (get-handshake-status ssl-engine)])

(defmethod handshake [:NEED_UNWRAP] [{:keys [ssl-engine packet-buffer-src app-buffer-dst] :as state} status]
  (println status)
  (.flip packet-buffer-src)
  (let [^SSLEngineResult result (.unwrap ssl-engine packet-buffer-src app-buffer-dst)]
    [state [:NEED_UNWRAP (get-result-status result)]]))

(defmethod handshake [:NEED_UNWRAP :OK] [{:keys [ssl-engine app-buffer-dst packet-buffer-src] :as state} status]
  (println status)
  (.compact packet-buffer-src)
  (.clear app-buffer-dst)
  [state (get-handshake-status ssl-engine)])

(defmethod handshake [:NEED_UNWRAP :BUFFER_UNDERFLOW] [{:keys [ssl-engine channel packet-buffer-src] :as state} status]
  (println status)
  (.compact packet-buffer-src)
  (let [net-size (-> (.getSession ssl-engine)
                     (.getPacketBufferSize))
        pbs (if (> net-size (.capacity packet-buffer-src))
              (doto (ByteBuffer/allocate net-size)
                (.put packet-buffer-src))
              packet-buffer-src)]
    (let [n (.read channel pbs)]
      [state [:NEED_UNWRAP]])))

(defmethod handshake [:NEED_UNWRAP :BUFFER_OVERFLOW] [{:keys [ssl-engine packet-buffer-src app-buffer-dst] :as state} status]
  (println status)
  (let [app-size (-> (.getSession ssl-engine)
                     (.getApplicationBufferSize))
        abd (ByteBuffer/allocate (+ app-size (.position app-buffer-dst)))]
    (.flip app-buffer-dst)
    (.put abd app-buffer-dst)
    (let [^SSLEngineResult result (.unwrap ssl-engine packet-buffer-src abd)]
      [(assoc state :app-buffer-dst abd) [:NEED_UNWRAP (get-result-status result)]])))

(defmethod handshake [:NEED_TASK] [{:keys [ssl-engine] :as state} status]
  (println status)
  (loop [task (.getDelegatedTask ssl-engine)]
    (when task (.run task)))
  [state (get-handshake-status ssl-engine)])

(defmethod handshake [:NOT_HANDSHAKING] [state status]
  (reduced [state status]))

(defmethod handshake :default [{:keys [ssl-engine] :as state} status]
  (println "here" (get-handshake-status ssl-engine) status)
  (reduced [state status]))

(defn connect
  [host port]
  (System/setProperty "https.protocols" "TLSv1.2")
  (let [ssl-ctx (SSLContext/getDefault)
        ssl-engine (doto
                     (.createSSLEngine ssl-ctx)
                     (.setUseClientMode true)
                     (.setEnableSessionCreation true))
        channel (doto
                  (SocketChannel/open)
                  (.connect (InetSocketAddress. host port))
                  (.configureBlocking true)
                  (.setOption StandardSocketOptions/TCP_NODELAY true))
        packet-buffer-src (ByteBuffer/allocate (-> (.getSession ssl-engine)
                                                   (.getPacketBufferSize)))
        packet-buffer-dst (ByteBuffer/allocate (-> (.getSession ssl-engine)
                                                   (.getPacketBufferSize)))
        app-buffer-src (ByteBuffer/allocate (-> (.getSession ssl-engine)
                                                (.getApplicationBufferSize)))
        app-buffer-dst (ByteBuffer/allocate (-> (.getSession ssl-engine)
                                                (.getApplicationBufferSize)))]

    (println (.getEnabledProtocols ssl-engine))
    (.beginHandshake ssl-engine)
    (loop [state {:ssl-ctx           ssl-ctx
                  :ssl-engine        ssl-engine
                  :channel           channel
                  :packet-buffer-src packet-buffer-src
                  :packet-buffer-dst packet-buffer-dst
                  :app-buffer-src    app-buffer-src
                  :app-buffer-dst    app-buffer-dst}
           status (get-handshake-status ssl-engine)]
      (let [result (handshake state status)]
        (if-not (reduced? result)
          (let [[state status] result]
            (recur state status))
          (do
            (.put app-buffer-src (.getBytes "GET /search?q=qwerty HTTP/1.0\r\n"))
            (.wrap ssl-engine app-buffer-src packet-buffer-dst)
            (.flip packet-buffer-dst)
            (.write channel packet-buffer-dst)
            (.compact packet-buffer-dst)
            (.read channel packet-buffer-src)
            (.flip packet-buffer-src)
            (.unwrap ssl-engine packet-buffer-src app-buffer-dst)
            (println (String. (.array app-buffer-dst)))))))))

(defn main
  []
  (with-open [selector (Selector/open)
              ^ServerSocketChannel socket-channel
              (doto (ServerSocketChannel/open)
                (.bind (InetSocketAddress. "localhost" 9999))
                (.configureBlocking false)
                (.register selector SelectionKey/OP_ACCEPT))]
    (while true
      (.select selector)
      (let [selected-keys (.selectedKeys selector)
            iter (.iterator selected-keys)]
        (while (.hasNext iter)
          (let [^SelectionKey k (.next iter)]
            (.remove iter)
            (cond
              (.isAcceptable k)
              (do
                (println k "acceptable")
                (when-let [channel (.accept socket-channel)]
                  (doto
                    channel
                    (.configureBlocking false)
                    (.register selector SelectionKey/OP_READ))))

              (.isReadable k)
              (do
                (println k "readable")
                (let [^SocketChannel channel (.channel k)
                      buff (ByteBuffer/allocate 256)]
                  (.read channel buff)
                  (.flip buff)
                  (.write channel buff)))

              (.isWritable k)
              (println k "writable"))))))))
