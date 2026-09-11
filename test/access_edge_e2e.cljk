#!/usr/bin/env nbb
(ns access-edge-e2e
  (:require [kekkai.node.access-edge :as access-edge]
            [kekkai.node.netmap :as netmap]
            [kekkai.node.relay-server :as relay-server]
            [kekkai.node.udp :as udp]
            [kotoba.bytes :as b]
            [noise.core :as noise]
            [noise.provider.node :as provider]
            ["node:fs" :as fs]
            ["node:http" :as http]
            ["node:os" :as os]
            ["node:path" :as path]))

(def suite (noise/suite (provider/ports)))

(defn- keypair []
  (let [{:keys [priv pub]} (noise/keypair suite)]
    {:priv (b/hex priv) :pub (b/hex pub)}))

(defn- write-netmap! [directory filename self-id peer-id self-key peer-key
                      relay-key relay-port]
  (let [value
        {:netmap/version 1
         :netmap/tailnet "private.test"
         :netmap/self {:node/id self-id :node/key (:pub self-key)
                       :node/overlay-ip
                       (if (= self-id "client") "100.96.0.1" "100.96.0.2")}
         :netmap/peers
         [{:node/id peer-id :node/key (:pub peer-key)
           :node/overlay-ip
           (if (= peer-id "client") "100.96.0.1" "100.96.0.2")
           :node/status "authorized"}]
         ;; The same directed edge is published to both participants.
         :netmap/edges
         [{:edge/from "client" :edge/to "connector"
           :edge/capabilities [:overlay :private-http]
           :edge/ports [443]}]
         :netmap/relays
         [{:relay/name "test" :relay/region "test"
           :relay/host "127.0.0.1" :relay/port relay-port
           :relay/key (:pub relay-key)}]}
        file (.join path directory filename)]
    (.writeFileSync fs file (pr-str value))
    file))

(defn- listen [server]
  (js/Promise.
   (fn [resolve reject]
     (.once server "error" reject)
     (.listen server 0 "127.0.0.1"
              #(resolve (.-port (.address server)))))))

(defn- get-text [port pathname]
  (js/Promise.
   (fn [resolve reject]
     (let [request
           (.get http
                 #js {:host "127.0.0.1" :port port :path pathname}
                 (fn [response]
                   (let [chunks (array)]
                     (.on response "data" #(.push chunks %))
                     (.on response "end"
                          #(resolve {:status (.-statusCode response)
                                     :body (.toString (.concat js/Buffer chunks)
                                                      "utf8")})))))]
       (.on request "error" reject)))))

(defn- wait-for [predicate]
  (let [deadline (+ (.getTime (js/Date.)) 15000)]
    (letfn [(step []
              (cond
                (predicate) (js/Promise.resolve true)
                (> (.getTime (js/Date.)) deadline) (js/Promise.resolve false)
                :else (-> (js/Promise.
                           (fn [resolve _] (js/setTimeout resolve 100)))
                          (.then step))))]
      (step))))

(defn -main []
  (println "\nKekkai private HTTP access E2E\n")
  (let [directory (.mkdtempSync fs (.join path (.tmpdir os) "kekkai-access-"))
        relay-key (keypair)
        client-key (keypair)
        connector-key (keypair)
        upstream
        (.createServer http
                       (fn [request response]
                         (.writeHead response 200
                                     #js {"content-type" "text/plain"})
                         (.end response
                               (str "private:" (.-method request) ":"
                                    (.-url request)))))
        resources (atom {:upstream upstream})]
    (letfn [(cleanup! [exit-code]
              (when-let [stop (some-> @resources :client :stop)] (stop))
              (when-let [stop (some-> @resources :connector :stop)] (stop))
              (when-let [stop (some-> @resources :relay :stop)] (stop))
              (.close upstream)
              (js/setTimeout #(.exit js/process exit-code) 100))
            (start-relay [upstream-port]
              (-> (relay-server/start
                   {:port 0 :host "127.0.0.1"
                    :static {:priv (b/unhex (:priv relay-key))
                             :pub (b/unhex (:pub relay-key))}
                    :region "test"
                    :prologue
                    (b/utf8-encode
                     (netmap/prologue-string
                      {:netmap/tailnet "private.test" :netmap/version 1}))})
                  (.then
                   (fn [relay]
                     (swap! resources assoc :relay relay)
                     {:relay relay :upstream-port upstream-port}))))
            (start-edges [{:keys [relay upstream-port]}]
              (let [relay-port (udp/local-port (:sock relay))
                    client-file
                    (write-netmap! directory "client.edn"
                                   "client" "connector" client-key connector-key
                                   relay-key relay-port)
                    connector-file
                    (write-netmap! directory "connector.edn"
                                   "connector" "client" connector-key client-key
                                   relay-key relay-port)
                    common {:allow-unsigned-netmap? true
                            :listen-port 0 :tick-ms 100
                            :policy {:rekey-timeout 1 :keepalive-timeout 3}
                            :disco {:call-me-maybe-ms 300
                                    :probe-timeout-ms 300
                                    :heartbeat-ms 1000}}]
                (js/Promise.all
                 #js [(access-edge/start
                       (merge
                        common
                        {:mode :connector :node/id "connector"
                         :static connector-key :netmap-file connector-file
                         :services
                         {"finance"
                          {:base-url (str "http://127.0.0.1:" upstream-port)
                           :port 443}}}))
                      (access-edge/start
                       (merge
                        common
                        {:mode :client :node/id "client"
                         :static client-key :netmap-file client-file
                         :http-listen-port 0
                         :services
                         {"finance" {:peer "connector" :port 443}}}))])))
            (exercise! [[connector client]]
              (swap! resources assoc :connector connector :client client)
              (-> (wait-for
                   #(and
                     (every? :established? (:peers ((:status connector))))
                     (every? :established? (:peers ((:status client))))))
                  (.then
                   (fn [connected?]
                     (when-not connected?
                       (throw (js/Error. "peer session timeout")))
                     (get-text (.-port (.address (:http-server client)))
                               "/finance/accounts?period=now")))
                  (.then
                   (fn [{:keys [status body]}]
                     (when-not
                      (and (= 200 status)
                           (= "private:GET:/accounts?period=now" body))
                       (throw
                        (js/Error.
                         (str "unexpected response " status " " body))))
                     (println
                      "  ok   local client → Noise overlay → connector")
                     (println
                      "  ok   connector preserved fixed upstream origin")
                     (println "\nPRIVATE ACCESS E2E PASSED\n")))))]
      (-> (listen upstream)
          (.then start-relay)
          (.then start-edges)
          (.then exercise!)
          (.then (fn [_] (cleanup! 0)))
          (.catch
           (fn [error]
             (println
             (str "\nPRIVATE ACCESS E2E FAILED: " (ex-message error)))
             (cleanup! 1)))))))

(-main)
