(ns dotd.socket
  (:require [clojure.core.async :as async])
  (:import (java.nio.channels Selector SocketChannel SelectionKey ServerSocketChannel)
           (java.net InetSocketAddress StandardSocketOptions)
           (java.nio ByteBuffer)
           (javax.net.ssl SSLEngine SSLContext SSLEngineResult SSLEngineResult$HandshakeStatus)
           (tlschannel SniSslContextFactory)))

(defn prepare-buffers
  [& buffs]
  (doseq [^ByteBuffer buff buffs]
    (doto buff
      (.flip))))

(defn enlarge-buffer
  [^ByteBuffer buffer size]
  (let [new-buff (ByteBuffer/allocate (+ size (.position buffer)))]
    (.flip buffer)
    (.put new-buff buffer)
    new-buff))

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
  (.clear packet-buffer-dst)
  (.flip app-buffer-src)
  (let [^SSLEngineResult result (.wrap ssl-engine app-buffer-src packet-buffer-dst)]
    [state [:NEED_WRAP (get-result-status result)]]))

(defmethod handshake [:NEED_WRAP :OK] [{:keys [ssl-engine channel packet-buffer-dst] :as state} status]
  (println status)
  (.flip packet-buffer-dst)
  (println packet-buffer-dst (String. (.array packet-buffer-dst)))
  (.write channel packet-buffer-dst)
  (.compact packet-buffer-dst)
  [state (get-handshake-status ssl-engine)])

(defmethod handshake [:NEED_UNWRAP] [{:keys [ssl-engine channel packet-buffer-src app-buffer-dst] :as state} status]
  (println status)
  (println packet-buffer-src)
  (.read channel packet-buffer-src)
  (.flip packet-buffer-src)
  (println packet-buffer-src (String. (.array packet-buffer-src)))
  (let [^SSLEngineResult result (.unwrap ssl-engine packet-buffer-src app-buffer-dst)]
    (println packet-buffer-src)
    [state [:NEED_UNWRAP (get-result-status result)]]))

(defmethod handshake [:NEED_UNWRAP :OK] [{:keys [ssl-engine app-buffer-dst packet-buffer-src] :as state} status]
  (println status)
  (.compact packet-buffer-src)
  (println (String. (.array app-buffer-dst)))
  [state (get-handshake-status ssl-engine)])

(defmethod handshake [:NEED_UNWRAP :BUFFER_UNDERFLOW] [{:keys [ssl-engine packet-buffer-src] :as state} status]
  (println status)
  (println packet-buffer-src)
  (let [net-size (-> (.getSession ssl-engine)
                     (.getPacketBufferSize))]
    (if (> net-size (.capacity packet-buffer-src))
      (do
        (let [pbs (doto (ByteBuffer/allocate net-size)
                    (.put packet-buffer-src))]
          (reduced [(assoc state :packet-buffer-src pbs) [:NEED_UNWRAP]])))
      (reduced [state [:NEED_UNWRAP]]))))

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

(defmethod handshake :default [{:keys [ssl-engine] :as state} status]
  (println "here" (get-handshake-status ssl-engine) status)
  (reduced [state status]))

(defn connect
  [host port]
  (System/setProperty "https.protocols" "TLSv1.2")
  (let [ssl-ctx (doto
                  (SSLContext/getInstance "TLSv1.2")
                  (.init nil nil nil))
        ssl-engine (doto
                     (.createSSLEngine ssl-ctx)
                     (.setUseClientMode true))
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
        (when-not (reduced? result)
          (let [[state status] result]
            (recur state status)))))))

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
