#!/usr/bin/env nbb
;; Run a relay. One publicly reachable UDP port; no state, no database.
;;
;;   nbb --classpath "src:..." bin/relay.cljs relay.edn
;;
;; relay.edn: {:port 41642 :region "jp-tyo-1"
;;             :static {:priv "<hex>" :pub "<hex>"}
;;             :tailnet "kekkai.example" :netmap-version 42}
;;
;; The :tailnet/:netmap-version pair must match what the nodes' netmap says, and
;; the :pub key must be what the netmap publishes as this relay's :relay/key —
;; clients authenticate the relay against it and will refuse to register
;; otherwise (which is the point).
(ns relay-main
  (:require [kekkai.node.netmap :as netmap]
            [kekkai.node.relay-server :as relay-server]
            [kotoba.bytes :as b]
            ["node:fs" :as fs]))

(defn -main [& args]
  (let [path (or (first args) "relay.edn")
        {:keys [port host region static tailnet netmap-version]}
        (cljs.reader/read-string (.readFileSync fs path "utf8"))]
    (-> (relay-server/start
         {:port port :host host :region region
          :static {:priv (b/unhex (:priv static)) :pub (b/unhex (:pub static))}
          :prologue (b/utf8-encode (netmap/prologue-string
                                    {:netmap/tailnet tailnet
                                     :netmap/version netmap-version}))})
        (.catch (fn [e]
                  (js/console.error (str "relay failed: " (or (ex-message e) e)))
                  (set! (.-exitCode js/process) 1))))))

(apply -main (drop 3 (js->clj (.-argv js/process))))
