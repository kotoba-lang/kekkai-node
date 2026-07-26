#!/usr/bin/env nbb
;; End-to-end over real UDP sockets: a relay process, two node agents, real Noise
;; IK sessions, a candidate exchange that upgrades the path from relayed to
;; direct, and a real DNS query answered from the netmap.
;;
;;   nbb --classpath "src:test:…" test/e2e.cljs
;;
;; This is a separate script rather than a cljs.test namespace because every step
;; is asynchronous and ordered; a test framework's async plumbing would obscure
;; what is being asserted. Exit code is 0 only if every check passed.
;;
;; What it does NOT prove, stated up front: both agents run on loopback, so the
;; "direct path" here traverses no NAT. The hole-punch *protocol* (candidate
;; signalling through the relay, the shared burst schedule, the pong that proves
;; an inbound path, the switch) is exercised end to end; whether a punch succeeds
;; against a particular pair of real NATs is a property of those NATs and can
;; only be measured on the real fleet. `disco/path-report` is the instrument for
;; that.
(ns e2e
  (:require [kekkai.node.agent :as agent]
            [kekkai.node.netmap :as netmap]
            [kekkai.node.relay-server :as relay-server]
            [kekkai.node.udp :as udp]
            [kotoba.bytes :as b]
            [nameserver.wire :as wire]
            [noise.core :as noise]
            [noise.provider.node :as provider]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def suite (noise/suite (provider/ports)))
(def failures (atom []))
(def notes (atom []))

(defn check [ok? label & [detail]]
  (if ok?
    (println (str "  ok   " label))
    (do (println (str "  FAIL " label (when detail (str " — " (pr-str detail)))))
        (swap! failures conj label)))
  ok?)

(defn note [s] (swap! notes conj s) (println (str "  ..   " s)))

(defn sleep [ms] (js/Promise. (fn [res] (js/setTimeout res ms))))

(defn wait-for
  "Poll `pred` until true or timeout. -> Promise of boolean."
  [pred {:keys [timeout-ms interval-ms] :or {timeout-ms 15000 interval-ms 100}}]
  (let [deadline (+ (.getTime (js/Date.)) timeout-ms)]
    (letfn [(step []
              (cond
                (pred) (js/Promise.resolve true)
                (> (.getTime (js/Date.)) deadline) (js/Promise.resolve false)
                :else (.then (sleep interval-ms) step)))]
      (step))))

(defn- hex-keypair [] (let [{:keys [priv pub]} (noise/keypair suite)]
                        {:priv (b/hex priv) :pub (b/hex pub)}))

(defn- netmap-for [self-id peer-id self-key peer-key relay-key relay-port version]
  {:netmap/version version
   :netmap/tailnet "kekkai.test"
   :netmap/self {:node/id self-id :node/key (:pub self-key)
                 :node/overlay-ip (if (= "asher" self-id) "100.64.0.1" "100.64.0.2")}
   ;; deliberately NO :node/endpoints — the only way these two find a direct
   ;; path is by signalling candidates to each other through the relay
   :netmap/peers [{:node/id peer-id :node/key (:pub peer-key)
                   :node/overlay-ip (if (= "asher" peer-id) "100.64.0.1" "100.64.0.2")
                   :node/status "authorized"}]
   :netmap/edges [{:edge/from self-id :edge/to peer-id
                   :edge/capabilities [:overlay :ssh]}]
   :netmap/relays [{:relay/name "test-1" :relay/region "test"
                    :relay/host "127.0.0.1" :relay/port relay-port
                    :relay/key (:pub relay-key)}]})

(defn- write-edn! [dir name data]
  (let [p (.join path dir name)]
    (.writeFileSync fs p (pr-str data))
    p))

(defn- dns-query-bytes [qname qtype]
  (wire/encode-message {:dns/id 0x4242 :dns/qr :query :dns/rd? true
                        :dns/questions [{:dns/qname qname :dns/qtype qtype
                                         :dns/qclass "IN"}]}))

(defn dns-query
  "Real UDP DNS query against `port`. -> Promise of the decoded response or nil."
  [port qname qtype]
  (js/Promise.
   (fn [resolve _]
     (-> (udp/socket
          {:on-message (fn [bytes _from]
                         (resolve (try (wire/decode-message bytes)
                                       (catch :default _ nil))))})
         (.then (fn [sock]
                  (udp/send! sock (dns-query-bytes qname qtype) (str "127.0.0.1:" port))
                  (js/setTimeout #(do (udp/close! sock) (resolve nil)) 3000)))))))

(defn -main []
  (println "\nkekkai-node E2E — relay + two agents + MagicDNS over real UDP\n")
  (let [dir (.mkdtempSync fs (.join path (.tmpdir os) "kekkai-e2e-"))
        relay-key (hex-keypair)
        a-key (hex-keypair)
        b-key (hex-keypair)
        a-events (atom [])
        b-events (atom [])
        state (atom {})]
    (-> (relay-server/start
         {:port 0 :host "127.0.0.1"
          :static {:priv (b/unhex (:priv relay-key)) :pub (b/unhex (:pub relay-key))}
          :region "test"
          ;; the relay handshake is bound to the same prologue the netmap defines
          :prologue (b/utf8-encode
                     (netmap/prologue-string {:netmap/tailnet "kekkai.test"
                                              :netmap/version 1}))
          :on-event (fn [e] (when (.-E2E_TRACE js/process.env) (println "R" (pr-str e))) (swap! state update :relay-events (fnil conj []) e))})
        (.then
         (fn [relay]
           (let [relay-port (udp/local-port (:sock relay))
                 _ (println (str "  relay listening on 127.0.0.1:" relay-port))
                 nm-a (netmap-for "asher" "judah" a-key b-key relay-key relay-port 1)
                 nm-b (netmap-for "judah" "asher" b-key a-key relay-key relay-port 1)
                 file-a (write-edn! dir "netmap-asher.edn" nm-a)
                 file-b (write-edn! dir "netmap-judah.edn" nm-b)
                 ;; tightened timers so an E2E takes seconds, not minutes
                 fast {:tick-ms 200
                       :policy {:rekey-timeout 1 :keepalive-timeout 3}
                       :disco {:call-me-maybe-ms 400 :probe-timeout-ms 400
                               :heartbeat-ms 1000}}]
             (swap! state assoc :relay relay)
             (-> (js/Promise.all
                  #js [(agent/start {:config (merge fast
                                                    {:node/id "asher"
                                                     :static a-key
                                                     :allow-unsigned-netmap? true
                                                     :netmap-file file-a
                                                     :listen-port 0
                                                     :dns {:enabled? true :port 0}})
                                     :on-event (fn [e] (when (.-E2E_TRACE js/process.env) (println "A" (pr-str e))) (swap! a-events conj e))})
                       (agent/start {:config (merge fast
                                                    {:node/id "judah"
                                                     :static b-key
                                                     :allow-unsigned-netmap? true
                                                     :netmap-file file-b
                                                     :listen-port 0})
                                     :on-event (fn [e] (when (.-E2E_TRACE js/process.env) (println "B" (pr-str e))) (swap! b-events conj e))})])
                 (.then
                  (fn [[a bnode]]
                    (swap! state assoc :a a :b bnode)
                    (println "  agents started")
                    (-> (wait-for #(and (:connected? (:relay ((:status a))))
                                        (:connected? (:relay ((:status bnode)))))
                                  {})
                        (.then (fn [ok]
                                 (check ok "both agents authenticate to the relay and register")))
                        (.then (fn [_]
                                 (wait-for #(and (every? :established? (:peers ((:status a))))
                                                 (every? :established? (:peers ((:status bnode)))))
                                           {})))
                        (.then (fn [ok]
                                 (check ok "Noise IK session established through the relay"
                                        (:peers ((:status a))))
                                 (check (= :relay (:route (first (:peers ((:status a))))))
                                        "the first path is the relay (connectivity before optimization)")))
                        ;; application data over the relayed path
                        (.then (fn [_]
                                 (swap! (:state a) agent/send-to "judah"
                                        (b/utf8-encode "hello over the relay"))
                                 (wait-for #(some (fn [e] (and (= :data (:event e))
                                                               (= "hello over the relay"
                                                                  (apply str (map char (:payload e))))))
                                                  @b-events)
                                           {:timeout-ms 5000})))
                        (.then (fn [ok]
                                 (check ok "sealed application data arrives over the relay")))
                        ;; the relay must not have been able to read it
                        (.then (fn [_]
                                 (let [fwd (filter #(= :forwarded (:event %))
                                                   (:relay-events @state))]
                                   (check (seq fwd) "the relay forwarded by destination key only"
                                          (first fwd)))))
                        ;; candidate exchange -> direct path
                        (.then (fn [_]
                                 (note "waiting for the candidate exchange to punch a direct path")
                                 (wait-for #(and (= :direct (:route (first (:peers ((:status a))))))
                                                 (= :direct (:route (first (:peers ((:status bnode)))))))
                                           {:timeout-ms 20000})))
                        (.then (fn [ok]
                                 (check ok "path upgraded from relay to direct on both sides"
                                        [(:peers ((:status a))) (:peers ((:status bnode)))])
                                 (let [p (first (:peers ((:status a))))]
                                   (check (number? (:latency-ms p))
                                          "the direct path has a measured latency" p))))
                        ;; data over the direct path, and the session survived the switch
                        (.then (fn [_]
                                 (let [before (count (filter #(= :data (:event %)) @b-events))]
                                   (swap! (:state a) agent/send-to "judah"
                                          (b/utf8-encode "hello over the punched path"))
                                   (-> (wait-for #(> (count (filter (fn [e] (= :data (:event e))) @b-events))
                                                     before)
                                                 {:timeout-ms 5000})
                                       (.then (fn [ok]
                                                (check ok "data flows over the direct path")
                                                (check (nil? (some #(= :established (:event %))
                                                                   (drop 1 (filter #(= :established (:event %))
                                                                                   @b-events))))
                                                       "the session was NOT re-handshaked to change path")))))))
                        ;; MagicDNS
                        (.then (fn [_]
                                 (dns-query (:dns-port a) "judah.kekkai.test." "A")))
                        (.then (fn [resp]
                                 (check (some? resp) "MagicDNS answered a real DNS query")
                                 (when resp
                                   (let [rr (first (:dns/answers resp))]
                                     (check (= "100.64.0.2" (get-in rr [:zone/rdata :zone/address]))
                                            "…with the peer's overlay address from the netmap" rr)))))
                        (.then (fn [_] (dns-query (:dns-port a) "www.example.com." "A")))
                        (.then (fn [resp]
                                 (check (= :refused (:dns/rcode resp))
                                        "names outside the tailnet are refused, not NXDOMAINed"
                                        (:dns/rcode resp))))
                        (.then (fn [_]
                                 ((:stop a)) ((:stop bnode)) ((:stop relay))
                                 (println (str "\n" (if (empty? @failures)
                                                      "E2E PASSED"
                                                      (str "E2E FAILED: " (count @failures) " check(s)"))
                                               "\n"))
                                 (when (seq @failures)
                                   (set! (.-exitCode js/process) 1))
                                 (js/setTimeout #(.exit js/process (if (empty? @failures) 0 1)) 200)))
                        (.catch (fn [e]
                                  (println (str "\nE2E ERROR: " (or (ex-message e) e)))
                                  (println (.-stack e))
                                  (set! (.-exitCode js/process) 1)
                                  (js/setTimeout #(.exit js/process 1) 200))))))))))
        (.catch (fn [e]
                  (println (str "E2E setup failed: " (or (ex-message e) e)))
                  (set! (.-exitCode js/process) 1))))))

(-main)
