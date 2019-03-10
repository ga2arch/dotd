(ns dotd.core
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as async])
  (:import (java.nio.channels DatagramChannel)
           (java.net InetSocketAddress)
           (java.nio ByteBuffer ByteOrder)
           (javax.net.ssl SSLSocketFactory SSLSocket)
           (java.io DataOutputStream DataInputStream))
  (:gen-class))

(def cloudflare "1.1.1.1")

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
      (let [packet (ByteBuffer/allocate 512)]
        (.order packet ByteOrder/BIG_ENDIAN)
        (.clear packet)
        (let [addr (.receive channel packet)]
          (.flip packet)
          (async/>! out {:addr   addr
                         :packet packet})))
      (recur))
    (async/go-loop []
      (let [{:keys [addr packet]} (async/<! in)]
        (.send channel packet addr)
        (recur)))
    {:in in :out out}))

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
  [{:keys [in out]} {:keys [addr ^ByteBuffer packet]}]
  (let [len (.remaining packet)]
    (doto out
      (.writeShort len)
      (.write (.array packet))
      (.flush))
    (let [buff (byte-array 512)]
      (.skipBytes in 2)
      (let [n (.read in buff)]
        {:packet (doto
                   (ByteBuffer/wrap buff)
                   (.order ByteOrder/BIG_ENDIAN)
                   (.position n)
                   (.flip))
         :addr   addr}))))

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
  (let [{:keys [in out]} (start-server)
        xf (map (partial dispatch cloudflare))]
    (async/pipeline-blocking 10 in xf out)
    (async/<!! (async/chan 1))))
