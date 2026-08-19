#!/usr/bin/env nbb
;; Can a forwarded service tell two peers apart? Three agents, one relay, real
;; Noise IK sessions, two loopback services on one host.
;;
;;   nbb --classpath "src:test:…" test/principal_e2e.cljs
;;
;; `stream_e2e.cljs` proves one forwarder carries bytes and that an
;; unauthorised OPEN is refused. This proves the question that comes next and
;; is not the same: the service side authorises a *proven peer key*, then hands
;; the request to a plain loopback socket, so the service itself sees
;; 127.0.0.1 and cannot name who reached it. Everything an application
;; downstream might use to identify the caller is gone by then.
;;
;; The claim under test is that **one port per peer** recovers the principal
;; without changing the protocol: if bot-b is granted only port P_b and bot-c
;; only P_c, then whoever arrives on P_b is bot-b, decided by the netmap rather
;; than by anything the caller said.
;;
;; It also checks the erasure it is working around, and the structural
;; separation the same netmap buys: bot-b and bot-c are listed in each other's
;; peers and have no edge, so they never establish a session at all.
(ns principal-e2e
  (:require [clojure.string :as str]
            [kekkai.node.agent :as agent]
            [kekkai.node.netmap :as netmap]
            [kekkai.node.relay-server :as relay-server]
            [kekkai.node.stream :as stream]
            [kekkai.node.stream-edge :as edge]
            [kekkai.node.udp :as udp]
            [kotoba.bytes :as b]
            [noise.core :as noise]
            [noise.provider.node :as provider]
            ["node:http" :as http]
            ["node:fs" :as fs]
            ["node:net" :as net]
            ["node:os" :as os]
            ["node:path" :as path]))

(def suite (noise/suite (provider/ports)))
(def failures (atom []))

(defn check [ok? label & [detail]]
  (if ok?
    (println (str "  ok   " label))
    (do (println (str "  FAIL " label (when detail (str " — " (pr-str detail)))))
        (swap! failures conj label)))
  ok?)

(defn note [s] (println (str "  ..   " s)))
(defn sleep [ms] (js/Promise. (fn [res] (js/setTimeout res ms))))

(defn wait-for [pred {:keys [timeout-ms interval-ms]
                      :or {timeout-ms 25000 interval-ms 100}}]
  (let [deadline (+ (.getTime (js/Date.)) timeout-ms)]
    (letfn [(step []
              (cond
                (pred) (js/Promise.resolve true)
                (> (.getTime (js/Date.)) deadline) (js/Promise.resolve false)
                :else (.then (sleep interval-ms) step)))]
      (step))))

(defn- hex-keypair [] (let [{:keys [priv pub]} (noise/keypair suite)]
                        {:priv (b/hex priv) :pub (b/hex pub)}))

(defn- write-edn! [dir name data]
  (let [p (.join path dir name)]
    (.writeFileSync fs p (pr-str data))
    p))

(defn- listening [server]
  (js/Promise.
   (fn [resolve _]
     (if-let [a (.address server)]
       (resolve (.-port a))
       (.on server "listening" #(resolve (.-port (.address server))))))))

;; ── the two services on the drive host ──────────────────────────────────────

(defn- identity-server
  "A TCP server that greets with `greeting` and records every connection's
   remote address. -> Promise of {:server :port :log}.

   The log is the instrument for the erasure claim: whatever the overlay
   proved about the caller, this is all the service is told."
  [greeting]
  (js/Promise.
   (fn [resolve _]
     (let [log (atom [])
           server (.createServer
                   net
                   (fn [sock]
                     (swap! log conj {:remote (.-remoteAddress sock)
                                      :at (.getTime (js/Date.))})
                     (.write sock greeting)))]
       (.listen server 0 "127.0.0.1"
                #(resolve {:server server :port (.-port (.address server))
                           :log log}))))))

(defn- http-identity-server
  "An HTTP service that answers with the peer `principal-of` names for the
  connection it is being asked over. -> Promise of {:server :port :log}.

  Two gaps close here at once. HTTP is the shape the ref plane actually has,
  and until now the forwarder had only carried a raw echo and ssh — a
  userspace stream that reassembles bytes correctly can still be wrong about
  half-close, which is the one thing an HTTP response depends on. And the
  handler asks `edge/principal-of` rather than reading `remoteAddress`, which
  is the whole point: the peer is recovered from the agent that proved it, not
  re-derived from a network address that says 127.0.0.1 either way.

  It reads the registry directly because it shares a process with the agent
  here. A service in its own process asks the agent over a local endpoint; the
  answer and its lifetime are the same."
  [registry]
  (js/Promise.
   (fn [resolve _]
     (let [log (atom [])
           server (.createServer
                   http
                   (fn [req res]
                     (let [sock (.-socket req)
                           who (:peer (edge/principal-of registry (.-remotePort sock)))]
                       (swap! log conj {:remote (.-remoteAddress sock)
                                        :port (.-remotePort sock)
                                        :principal who})
                       (if (str/includes? (str (.-url req)) "eof")
                         ;; No Content-Length and no chunking: the body ends
                         ;; when the connection does. This is the only shape
                         ;; that makes the client depend on half-close, and
                         ;; without it the forwarder can drop EOF entirely and
                         ;; an ordinary fetch still succeeds — measured.
                         (do (set! (.-useChunkedEncodingByDefault res) false)
                             (.writeHead res 200 #js {"content-type" "text/plain"
                                                      "connection" "close"})
                             (.end res (str "eof-principal=" (or who "unknown") "\n")))
                         (do (.writeHead res 200 #js {"content-type" "text/plain"})
                             (.end res (str "principal=" (or who "unknown") "\n")))))))]
       (.listen server 0 "127.0.0.1"
                #(resolve {:server server :port (.-port (.address server))
                           :log log}))))))

(defn- fetch-bounded
  "`fetch`, but a hang becomes a FAILURE rather than a hung harness.

  Not decoration: breaking half-close on purpose made the EOF leg below wait
  forever, and an unbounded probe turns a discriminating result into a stopped
  run that says nothing. The deadline is the check."
  [url ms]
  (js/Promise.race
   #js [(-> (js/fetch url)
            (.then (fn [r] (.then (.text r) (fn [t] {:status (.-status r) :body t}))))
            (.catch (fn [e] {:status -1 :body (str e)})))
        (js/Promise. (fn [res _]
                       (js/setTimeout #(res {:status -2 :body "timed out"}) ms)))]))

(defn- read-greeting
  "Connect to `port`, read until `n` bytes or timeout. -> Promise of the string."
  [port n timeout-ms]
  (js/Promise.
   (fn [resolve _]
     (let [got (atom "")
           sock (.connect net #js {:port port :host "127.0.0.1"})
           done (fn [] (when-not (.-destroyed sock) (.destroy sock)) (resolve @got))]
       (.on sock "data" (fn [chunk]
                          (swap! got str (.toString chunk "utf8"))
                          (when (>= (count @got) n) (done))))
       (.on sock "error" (fn [_] (resolve @got)))
       (js/setTimeout done timeout-ms)))))

;; ── the netmap ──────────────────────────────────────────────────────────────

(def ^:private overlay-ip
  {"drive" "100.64.0.1" "bot-b" "100.64.0.2" "bot-c" "100.64.0.3"})

(defn- netmap-for
  "One netmap, viewed from `self-id`.

  bot-b reaches the drive host on port-b and nothing else; bot-c on port-c and
  nothing else. There is deliberately **no edge between bot-b and bot-c** —
  the star through the service is structural, not a convention, and the peers
  list still names them so that what prevents the session is provably the edge
  rather than an omission."
  [self-id keys relay-key relay-port port-b port-c]
  (let [ids ["drive" "bot-b" "bot-c"]]
    {:netmap/version 1
     :netmap/tailnet "kekkai.test"
     :netmap/self {:node/id self-id :node/key (:pub (get keys self-id))
                   :node/overlay-ip (overlay-ip self-id)}
     :netmap/peers (vec (for [id ids :when (not= id self-id)]
                          {:node/id id :node/key (:pub (get keys id))
                           :node/overlay-ip (overlay-ip id)
                           :node/status "authorized"}))
     :netmap/edges [{:edge/from "bot-b" :edge/to "drive"
                     :edge/capabilities [:overlay :private-http]
                     :edge/ports [port-b]}
                    {:edge/from "bot-c" :edge/to "drive"
                     :edge/capabilities [:overlay :private-http]
                     :edge/ports [port-c]}
                    {:edge/from "drive" :edge/to "bot-b" :edge/capabilities [:overlay]}
                    {:edge/from "drive" :edge/to "bot-c" :edge/capabilities [:overlay]}]
     :netmap/relays [{:relay/name "test-1" :relay/region "test"
                      :relay/host "127.0.0.1" :relay/port relay-port
                      :relay/key (:pub relay-key)}]}))

;; ── the run ─────────────────────────────────────────────────────────────────

(defn- open-frame
  "A well-formed OPEN for `capability` on `port`, as a peer would send it."
  [stream-id capability port]
  {:kind :open :stream-id stream-id :seq 0 :ack 0 :window 32768
   :payload (vec (b/utf8-encode (str (name capability) " " port)))})

(defn -main [& _args]
  (println "\nkekkai-node principal E2E — can a forwarded service tell two peers apart?\n")
  (let [dir (.mkdtempSync fs (.join path (.tmpdir os) "kekkai-principal-e2e-"))
        relay-key (hex-keypair)
        keys {"drive" (hex-keypair) "bot-b" (hex-keypair) "bot-c" (hex-keypair)}
        reg {"drive" (atom {}) "bot-b" (atom {}) "bot-c" (atom {})}
        st (atom {})]
    (-> (js/Promise.all #js [(http-identity-server (get reg "drive"))
                             (identity-server "principal=bot-c\n")])
        (.then
         (fn [[svc-b svc-c]]
           (swap! st assoc :svc-b svc-b :svc-c svc-c)
           (println (str "  service for bot-b on 127.0.0.1:" (:port svc-b)))
           (println (str "  service for bot-c on 127.0.0.1:" (:port svc-c)))
           (relay-server/start
            {:port 0 :host "127.0.0.1"
             :static {:priv (b/unhex (:priv relay-key)) :pub (b/unhex (:pub relay-key))}
             :region "test"
             :prologue (b/utf8-encode
                        (netmap/prologue-string {:netmap/tailnet "kekkai.test"
                                                 :netmap/version 1}))
             :on-event (fn [_])})))
        (.then
         (fn [relay]
           (swap! st assoc :relay relay)
           (let [relay-port (udp/local-port (:sock relay))
                 port-b (:port (:svc-b @st))
                 port-c (:port (:svc-c @st))
                 _ (println (str "  relay on 127.0.0.1:" relay-port))
                 fast {:tick-ms 200
                       :policy {:rekey-timeout 1 :keepalive-timeout 3}
                       :disco {:call-me-maybe-ms 400 :probe-timeout-ms 400
                               :heartbeat-ms 1000}}
                 start-one
                 (fn [id]
                   (agent/start
                    {:config (merge fast
                                    {:node/id id :static (get keys id)
                                     :allow-unsigned-netmap? true
                                     :netmap-file (write-edn!
                                                   dir (str "nm-" id ".edn")
                                                   (netmap-for id keys relay-key
                                                               relay-port port-b port-c))
                                     :listen-port 0})
                     :on-event (fn [e]
                                 (when (and (= :data (:event e)) (get @st (keyword id)))
                                   (edge/on-peer-data (get reg id)
                                                      (get @st (keyword id)) e)))}))]
             (js/Promise.all #js [(start-one "drive") (start-one "bot-b")
                                  (start-one "bot-c")]))))
        (.then
         (fn [[drive bot-b bot-c]]
           (swap! st assoc :drive drive :bot-b bot-b :bot-c bot-c)
           (swap! st assoc
                  :timers (mapv (fn [id]
                                  (js/setInterval
                                   #(edge/tick! (get reg id) (get @st (keyword id)))
                                   50))
                                ["drive" "bot-b" "bot-c"]))
           (note "waiting for both bots to establish a session with the drive host")
           (wait-for
            (fn []
              (let [established (fn [h]
                                  (->> ((:status h)) :peers
                                       (filter :established?) (map :peer) set))]
                (and (contains? (established (:bot-b @st)) "drive")
                     (contains? (established (:bot-c @st)) "drive"))))
            {})))
        (.then
         (fn [up?]
           (check up? "both bots established a Noise session with the drive host")
           ;; D2 of ADR-2608198100: no bot-to-bot edge, so no bot-to-bot session.
           ;; Asserted here rather than assumed, because the peers list names
           ;; them — if the edge were not what stops it, this would pass anyway
           ;; and say nothing.
           (let [established (fn [h] (->> ((:status h)) :peers
                                          (filter :established?) (map :peer) set))]
             (check (not (contains? (established (:bot-b @st)) "bot-c"))
                    "bot-b has no session with bot-c, though it is in the netmap"
                    {:established (established (:bot-b @st))})
             (check (not (contains? (established (:bot-c @st)) "bot-b"))
                    "bot-c has no session with bot-b"
                    {:established (established (:bot-c @st))}))
           (let [fb (edge/forward! (get reg "bot-b") (:bot-b @st)
                                   {:listen-port 0 :peer "drive"
                                    :port (:port (:svc-b @st))
                                    :capability :private-http})
                 fc (edge/forward! (get reg "bot-c") (:bot-c @st)
                                   {:listen-port 0 :peer "drive"
                                    :port (:port (:svc-c @st))
                                    :capability :private-http})]
             (swap! st assoc :fwd-b fb :fwd-c fc)
             (js/Promise.all #js [(listening fb) (listening fc)]))))
        (.then
         (fn [[pb pc]]
           (swap! st assoc :fwd-b-port pb :fwd-c-port pc)
           ;; bot-b speaks HTTP through the forwarder; bot-c reads a raw
           ;; greeting. Deliberately different, so a pass says the mechanism is
           ;; not specific to either protocol.
           (js/Promise.all
            #js [(fetch-bounded (str "http://127.0.0.1:" pb "/whoami") 20000)
                 (read-greeting pc 15 15000)])))
        (.then
         (fn [[got-b got-c]]
           ;; The joint that was prose until now: a real HTTP client, over the
           ;; userspace stream, end to end. Stated narrowly — a framed response
           ;; carries its own length, so this says nothing about EOF. The
           ;; `/eof` leg below is the one that does, and it exists because
           ;; breaking half-close deliberately left THIS check green.
           (check (= 200 (:status got-b))
                  "an ordinary HTTP client completes a length-framed request through the forwarder"
                  {:status (:status got-b) :body (:body got-b)})
           (check (str/includes? (str (:body got-b)) "principal=bot-b")
                  "…and the service recovers the proven peer, not 127.0.0.1"
                  {:body (:body got-b)})
           (check (str/includes? got-c "principal=bot-c")
                  "bot-c's forward lands on the service granted to bot-c" {:got got-c})
           ;; The erasure this whole arrangement is working around. If the
           ;; service could name the peer, one port per peer would be an
           ;; optimisation rather than the mechanism.
           (let [remotes (set (map :remote (concat @(:log (:svc-b @st))
                                                   @(:log (:svc-c @st)))))]
             (check (= #{"127.0.0.1"} remotes)
                    "the address the service is told is still only 127.0.0.1"
                    {:remotes remotes}))
           ;; The pair is the finding: the address says nothing and the lookup
           ;; says everything. Asserting only the second would pass just as
           ;; well on a transport that never erased anything, and would stop
           ;; being evidence about this one.
           (check (= #{"bot-b"} (set (map :principal @(:log (:svc-b @st)))))
                  "…and principal-of recovers bot-b from the source port anyway"
                  {:log @(:log (:svc-b @st))})
           ;; The half-close leg, fetched now so its result is awaited before
           ;; the refusal checks below reset the counters.
           (fetch-bounded (str "http://127.0.0.1:" (:fwd-b-port @st) "/eof") 15000)))
        (.then
         (fn [got-eof]
           (check (and (= 200 (:status got-eof))
                       (str/includes? (str (:body got-eof)) "eof-principal=bot-b"))
                  "a response delimited only by EOF arrives — half-close survives the forwarder"
                  got-eof)
           ;; Now the check that matters: a peer asking the drive host for the
           ;; *other* peer's port. Injected as a raw OPEN so the forwarder's own
           ;; courtesy check cannot be what refuses it.
           (swap! st assoc
                  :before-b (count @(:log (:svc-b @st)))
                  :before-c (count @(:log (:svc-c @st))))
           (edge/on-peer-data (get reg "drive") (:drive @st)
                              {:peer "bot-b"
                               :payload (stream/encode
                                         (open-frame 7001 :private-http
                                                     (:port (:svc-c @st))))})
           (edge/on-peer-data (get reg "drive") (:drive @st)
                              {:peer "bot-c"
                               :payload (stream/encode
                                         (open-frame 7002 :private-http
                                                     (:port (:svc-b @st))))})
           ;; Read the registry *now*, in the same tick. `handle-open!` inserts
           ;; the entry synchronously before the socket connects, so admission
           ;; is visible here and only here. Read it after an await instead and
           ;; the answer stops discriminating: an admitted stream is torn down
           ;; moments later anyway, because the peer this OPEN was forged from
           ;; has no matching stream and answers `:no-such-stream`. Measured —
           ;; the first version of this probe checked after 800 ms, reported
           ;; `refused` for an OPEN the drive host had accepted and connected,
           ;; and only the connection counter below caught it.
           (swap! st assoc
                  :admitted-b (some? (get @(get reg "drive") ["bot-b" 7001]))
                  :admitted-c (some? (get @(get reg "drive") ["bot-c" 7002])))
           (sleep 800)))
        (.then
         (fn [_]
           (check (not (:admitted-b @st))
                  "bot-b asking for bot-c's port is refused at the drive host")
           (check (not (:admitted-c @st))
                  "bot-c asking for bot-b's port is refused at the drive host")
           ;; A refusal that still connected would be worse than no refusal: the
           ;; service would have been reached and only the reply withheld.
           (check (= (:before-c @st) (count @(:log (:svc-c @st))))
                  "…and bot-c's service was never connected to"
                  {:before (:before-c @st) :after (count @(:log (:svc-c @st)))})
           (check (= (:before-b @st) (count @(:log (:svc-b @st))))
                  "…and bot-b's service was never connected to"
                  {:before (:before-b @st) :after (count @(:log (:svc-b @st)))})
           (doseq [t (:timers @st)] (js/clearInterval t))
           (.close (:fwd-b @st)) (.close (:fwd-c @st))
           (.close (:server (:svc-b @st))) (.close (:server (:svc-c @st)))
           ((:stop (:drive @st))) ((:stop (:bot-b @st))) ((:stop (:bot-c @st)))
           ((:stop (:relay @st)))
           (sleep 300)))
        (.then
         (fn [_]
           (println)
           (if (seq @failures)
             (do (println (str "PRINCIPAL E2E FAILED: " (str/join ", " @failures)))
                 (set! (.-exitCode js/process) 1))
             (println "PRINCIPAL E2E PASSED"))
           (js/setTimeout #(.exit js/process (if (seq @failures) 1 0)) 200)))
        (.catch
         (fn [e]
           (println (str "\nPRINCIPAL E2E ERROR: " e "\n" (.-stack e)))
           (set! (.-exitCode js/process) 1)
           (js/setTimeout #(.exit js/process 1) 200))))))

(apply -main (drop 3 (js->clj (.-argv js/process))))
