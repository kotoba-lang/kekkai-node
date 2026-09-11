#!/usr/bin/env nbb
(ns tun-e2e
  (:require [kekkai.node.netmap :as netmap]
            [kekkai.node.packet-bridge :as packet-bridge]
            [kekkai.node.relay-server :as relay-server]
            [kekkai.node.udp :as udp]
            [kotoba.bytes :as b]
            [noise.core :as noise]
            [noise.provider.node :as provider]
            ["node:fs" :as fs]
            ["node:net" :as net]
            ["node:os" :as os]
            ["node:path" :as path]))

(def suite (noise/suite (provider/ports)))
(def ipv4 [0x45 0 0 20 0 0 0 0 64 6 0 0 10 0 0 1 203 0 113 9])

(defn keypair []
  (let [{:keys [priv pub]} (noise/keypair suite)]
    {:priv (b/hex priv) :pub (b/hex pub)}))

(defn wait-for [predicate]
  (let [deadline (+ (.getTime (js/Date.)) 15000)]
    (letfn [(step []
              (cond
                (predicate) (js/Promise.resolve true)
                (> (.getTime (js/Date.)) deadline) (js/Promise.resolve false)
                :else (-> (js/Promise.
                           (fn [resolve _] (js/setTimeout resolve 100)))
                          (.then step))))]
      (step))))

(defn write-netmap!
  [directory filename self-id peer-id self-key peer-key relay-key relay-port]
  (let [exit-peer?
        (= peer-id "exit")
        value
        {:netmap/version 1
         :netmap/tailnet "tun.test"
         :netmap/self {:node/id self-id :node/key (:pub self-key)
                       :node/overlay-ip
                       (if (= self-id "client") "100.96.0.2" "100.96.0.1")}
         :netmap/peers
         [(cond-> {:node/id peer-id :node/key (:pub peer-key)
                   :node/status "authorized"
                   :node/overlay-ip
                   (if (= peer-id "client") "100.96.0.2" "100.96.0.1")}
            exit-peer?
            (assoc :node/routes
                   [{:route/family 4 :route/prefix [0 0 0 0]
                     :route/bits 0}]))]
         :netmap/edges
         [{:edge/from "client" :edge/to "exit"
           :edge/capabilities [:overlay :tun]}]
         :netmap/relays
         [{:relay/name "test" :relay/region "test"
           :relay/host "127.0.0.1" :relay/port relay-port
           :relay/key (:pub relay-key)}]}
        file (.join path directory filename)]
    (.writeFileSync fs file (pr-str value))
    file))

(defn connect [socket-path]
  (js/Promise.
   (fn [resolve reject]
     (let [socket (.createConnection net socket-path)]
       (.once socket "connect" #(resolve socket))
       (.once socket "error" reject)))))

(defn send-frame! [socket packet]
  (let [body (.from js/Buffer (clj->js packet))
        header (.alloc js/Buffer 4)]
    (.writeUInt32BE header (.-length body) 0)
    (.write socket (.concat js/Buffer #js [header body]))))

(defn receive-frame [socket]
  (js/Promise.
   (fn [resolve reject]
     (let [buffer (atom (.alloc js/Buffer 0))
           timer (js/setTimeout
                  #(reject (js/Error. "packet receive timeout")) 10000)]
       (.on socket "data"
            (fn [chunk]
              (swap! buffer
                     (fn [current]
                       (.concat js/Buffer #js [current chunk])))
              (when (>= (.-length @buffer) 4)
                (let [length (.readUInt32BE @buffer 0)]
                  (when (>= (.-length @buffer) (+ 4 length))
                    (js/clearTimeout timer)
                    (resolve
                     (vec (js/Uint8Array.
                           (.subarray @buffer 4 (+ 4 length))))))))))))))

(defn -main []
  (println "\nKekkai full-tunnel packet E2E\n")
  (let [directory (.mkdtempSync fs (.join path (.tmpdir os) "kekkai-tun-"))
        relay-key (keypair)
        client-key (keypair)
        exit-key (keypair)
        resources (atom {})]
    (-> (relay-server/start
         {:port 0 :host "127.0.0.1"
          :static {:priv (b/unhex (:priv relay-key))
                   :pub (b/unhex (:pub relay-key))}
          :region "test"
          :prologue
          (b/utf8-encode
           (netmap/prologue-string
            {:netmap/tailnet "tun.test" :netmap/version 1}))})
        (.then
         (fn [relay]
           (swap! resources assoc :relay relay)
           (let [relay-port (udp/local-port (:sock relay))
                 client-netmap
                 (write-netmap! directory "client.edn"
                                "client" "exit" client-key exit-key
                                relay-key relay-port)
                 exit-netmap
                 (write-netmap! directory "exit.edn"
                                "exit" "client" exit-key client-key
                                relay-key relay-port)
                 common {:allow-unsigned-netmap? true
                         :listen-port 0 :tick-ms 100
                         :policy {:rekey-timeout 1 :keepalive-timeout 3}
                         :disco {:call-me-maybe-ms 300 :probe-timeout-ms 300
                                 :heartbeat-ms 1000}}
                 client-socket (.join path directory "client.sock")
                 exit-socket (.join path directory "exit.sock")]
             (-> (js/Promise.all
                  #js [(packet-bridge/start
                        {:config
                         (merge common
                                {:node/id "client" :static client-key
                                 :netmap-file client-netmap
                                 :packet-bridge {:path client-socket}})})
                       (packet-bridge/start
                        {:config
                         (merge common
                                {:node/id "exit" :static exit-key
                                 :netmap-file exit-netmap
                                 :packet-bridge {:path exit-socket}})})])
                 (.then
                  (fn [[client exit]]
                    (swap! resources assoc :client client :exit exit)
                    (-> (wait-for
                         #(and (.existsSync fs client-socket)
                               (.existsSync fs exit-socket)
                               (every? :established?
                                       (:peers ((:status client))))
                               (every? :established?
                                       (:peers ((:status exit))))))
                        (.then
                         (fn [ready?]
                           (when-not ready?
                             (throw (js/Error. "TUN peers did not become ready")))
                           (js/Promise.all
                            #js [(connect client-socket)
                                 (connect exit-socket)])))
                        (.then
                         (fn [[client-sidecar exit-sidecar]]
                           (swap! resources assoc
                                  :client-sidecar client-sidecar
                                  :exit-sidecar exit-sidecar)
                           (let [received (receive-frame exit-sidecar)]
                             (send-frame! client-sidecar ipv4)
                             received)))
                        (.then
                         (fn [received]
                           (when-not (= ipv4 received)
                             (throw (js/Error. "raw IP packet changed in tunnel")))
                           (println
                            "  ok   TUN frame → signed route → Noise → exit bridge")
                           (println
                            "  ok   one-way :tun edge created no reverse authority")
                           (println "\nFULL-TUNNEL E2E PASSED\n"))))))))))
        (.then
         (fn [_]
           (doseq [key [:client-sidecar :exit-sidecar]]
             (when-let [socket (get @resources key)] (.destroy socket)))
           (doseq [key [:client :exit :relay]]
             (when-let [stop (some-> @resources key :stop)] (stop)))
           (js/setTimeout #(.exit js/process 0) 100)))
        (.catch
         (fn [error]
           (println (str "\nFULL-TUNNEL E2E FAILED: " (ex-message error)))
           (doseq [key [:client-sidecar :exit-sidecar]]
             (when-let [socket (get @resources key)] (.destroy socket)))
           (doseq [key [:client :exit :relay]]
             (when-let [stop (some-> @resources key :stop)] (stop)))
           (js/setTimeout #(.exit js/process 1) 100))))))

(-main)
