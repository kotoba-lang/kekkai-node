#!/usr/bin/env nbb
;; TCP forwarded over the real overlay: a relay, two agents, real Noise IK
;; sessions, and a real TCP service reached through a real local listener.
;;
;;   nbb --classpath "src:test:…" test/stream_e2e.cljs
;;
;; `stream_test.cljc` proves the state machine against a simulated network that
;; loses and reorders. This proves the other half: that the machine is wired to
;; actual sockets correctly, that authorisation is applied on the receiving
;; side, and that a byte written to a local socket comes out of a remote one
;; unchanged. Neither test substitutes for the other — a correct protocol
;; wired to the wrong socket is still a broken forwarder.
;;
;; The service here is a plain TCP echo/bulk server rather than sshd, so the
;; test runs anywhere. `--ssh` additionally drives a real `ssh` client through
;; the forwarder when a reachable sshd is named, which is the claim that
;; actually matters and is reported separately rather than folded in.
(ns stream-e2e
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
            ["node:child_process" :as cp]
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
                      :or {timeout-ms 20000 interval-ms 100}}]
  (let [deadline (+ (.getTime (js/Date.)) timeout-ms)]
    (letfn [(step []
              (cond
                (pred) (js/Promise.resolve true)
                (> (.getTime (js/Date.)) deadline) (js/Promise.resolve false)
                :else (.then (sleep interval-ms) step)))]
      (step))))

(defn- hex-keypair [] (let [{:keys [priv pub]} (noise/keypair suite)]
                        {:priv (b/hex priv) :pub (b/hex pub)}))

(defn- netmap-for
  "Two nodes.

  Both directions carry `:overlay`, because the agent dials on
  `dialable`/`:overlay` and a node with no outbound edge never opens a session
  at all. Only asher→judah carries `:ssh`, and only on the service port — that
  asymmetry is what makes the two refusal checks meaningful rather than
  vacuous: judah could not open an ssh stream back even though its session is
  perfectly good."
  [self-id peer-id self-key peer-key relay-key relay-port service-port]
  {:netmap/version 1
   :netmap/tailnet "kekkai.test"
   :netmap/self {:node/id self-id :node/key (:pub self-key)
                 :node/overlay-ip (if (= "asher" self-id) "100.64.0.1" "100.64.0.2")}
   :netmap/peers [{:node/id peer-id :node/key (:pub peer-key)
                   :node/overlay-ip (if (= "asher" peer-id) "100.64.0.1" "100.64.0.2")
                   :node/status "authorized"}]
   :netmap/edges [{:edge/from "asher" :edge/to "judah"
                   :edge/capabilities [:overlay :ssh]
                   :edge/ports [service-port]}
                  {:edge/from "judah" :edge/to "asher"
                   :edge/capabilities [:overlay]}]
   :netmap/relays [{:relay/name "test-1" :relay/region "test"
                    :relay/host "127.0.0.1" :relay/port relay-port
                    :relay/key (:pub relay-key)}]})

(defn- listening
  "-> Promise of the bound port. `net.Server.listen` is asynchronous, so
   `.address` is null until it fires — reading it straight after `forward!`
   returns is a race that happens to lose every time."
  [server]
  (js/Promise.
   (fn [resolve _]
     (if-let [a (.address server)]
       (resolve (.-port a))
       (.on server "listening" #(resolve (.-port (.address server))))))))

(defn- write-edn! [dir name data]
  (let [p (.join path dir name)]
    (.writeFileSync fs p (pr-str data))
    p))

;; ── the service being forwarded ─────────────────────────────────────────────

(defn- echo-server
  "A TCP server that echoes every byte back. -> Promise of [server port]."
  []
  (js/Promise.
   (fn [resolve _]
     (let [server (.createServer net (fn [sock] (.pipe sock sock)))]
       (.listen server 0 "127.0.0.1"
                #(resolve [server (.-port (.address server))]))))))

(defn- tcp-exchange
  "Connect, write `payload`, read until `expect-bytes` arrive or timeout.
   -> Promise of the bytes received (a vector)."
  [port payload expect-bytes timeout-ms]
  (js/Promise.
   (fn [resolve _]
     (let [got (atom [])
           sock (.connect net #js {:port port :host "127.0.0.1"})
           done (fn [] (when-not (.-destroyed sock) (.destroy sock)) (resolve @got))]
       (.on sock "connect" (fn [] (.write sock (.from js/Buffer (clj->js payload)))))
       (.on sock "data" (fn [chunk]
                          (swap! got into (vec (js/Uint8Array. chunk)))
                          (when (>= (count @got) expect-bytes) (done))))
       (.on sock "error" (fn [_] (resolve @got)))
       (js/setTimeout done timeout-ms)))))

(defn- tcp-refused?
  "-> Promise of true when the local listener drops the connection without
   sending anything, which is what an unauthorised forward must do."
  [port]
  (js/Promise.
   (fn [resolve _]
     (let [sock (.connect net #js {:port port :host "127.0.0.1"})
           got (atom false)]
       (.on sock "data" (fn [_] (reset! got true)))
       (.on sock "error" (fn [_] (resolve true)))
       (.on sock "close" (fn [] (resolve (not @got))))
       (js/setTimeout (fn [] (.destroy sock) (resolve (not @got))) 3000)))))

;; ── optional: a real ssh client through the forwarder ───────────────────────

(defn- ssh-through
  "Run `ssh -p <port> -o … localhost true`. -> Promise of {:code :err}."
  [port user]
  (js/Promise.
   (fn [resolve _]
     (let [args #js ["-p" (str port)
                     "-o" "StrictHostKeyChecking=no"
                     "-o" "UserKnownHostsFile=/dev/null"
                     "-o" "BatchMode=yes"
                     "-o" "ConnectTimeout=10"
                     (str user "@127.0.0.1")
                     "echo kekkai-ssh-ok"]
           ps (.spawn cp "ssh" args)
           out (atom "") err (atom "")]
       (.on (.-stdout ps) "data" #(swap! out str (str %)))
       (.on (.-stderr ps) "data" #(swap! err str (str %)))
       (.on ps "close" (fn [code] (resolve {:code code :out @out :err @err})))
       (.on ps "error" (fn [e] (resolve {:code -1 :out @out :err (str e)})))))))

(defn -main [& args]
  (println "\nkekkai-node stream E2E — TCP forwarded over the real overlay\n")
  (let [ssh-target (second (drop-while #(not= "--ssh" %) args))
        dir (.mkdtempSync fs (.join path (.tmpdir os) "kekkai-stream-e2e-"))
        relay-key (hex-keypair) a-key (hex-keypair) b-key (hex-keypair)
        reg-a (atom {}) reg-b (atom {})
        st (atom {})]
    (-> (echo-server)
        (.then
         (fn [[echo service-port]]
           (swap! st assoc :echo echo :service-port service-port)
           (println (str "  echo service on 127.0.0.1:" service-port))
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
                 service-port (:service-port @st)
                 _ (println (str "  relay on 127.0.0.1:" relay-port))
                 nm-a (netmap-for "asher" "judah" a-key b-key relay-key relay-port
                                  service-port)
                 nm-b (netmap-for "judah" "asher" b-key a-key relay-key relay-port
                                  service-port)
                 fast {:tick-ms 200
                       :policy {:rekey-timeout 1 :keepalive-timeout 3}
                       :disco {:call-me-maybe-ms 400 :probe-timeout-ms 400
                               :heartbeat-ms 1000}}]
             (js/Promise.all
              #js [(agent/start
                    {:config (merge fast {:node/id "asher" :static a-key
                                          :allow-unsigned-netmap? true
                                          :netmap-file (write-edn! dir "nm-a.edn" nm-a)
                                          :listen-port 0})
                     :on-event (fn [e]
                                 (when (and (= :data (:event e)) (:a @st))
                                   (edge/on-peer-data reg-a (:a @st) e)))})
                   (agent/start
                    {:config (merge fast {:node/id "judah" :static b-key
                                          :allow-unsigned-netmap? true
                                          :netmap-file (write-edn! dir "nm-b.edn" nm-b)
                                          :listen-port 0})
                     :on-event (fn [e]
                                 (when (and (= :data (:event e)) (:b @st))
                                   (edge/on-peer-data reg-b (:b @st) e)))})]))))
        (.then
         (fn [[a bnode]]
           (swap! st assoc :a a :b bnode)
           ;; The agents' on-event closures read (:a @st)/(:b @st), which are set
           ;; only now — the handle does not exist when start is called. Frames
           ;; before this point would be dropped, so nothing is sent until the
           ;; sessions are up, which is the next wait anyway.
           (swap! st assoc
                  :timer-a (js/setInterval #(edge/tick! reg-a (:a @st)) 50)
                  :timer-b (js/setInterval #(edge/tick! reg-b (:b @st)) 50))
           (note "waiting for both Noise sessions to establish")
           ;; Through the agent's own `:status`, not by reaching into its state
           ;; atom: the internal peer map is keyed and shaped for the agent, and
           ;; a test that reads it is asserting on an implementation detail that
           ;; happened to be shaped the way the test guessed.
           (wait-for
            (fn []
              (let [pa (:peers ((:status (:a @st))))
                    pb (:peers ((:status (:b @st))))]
                (and (seq pa) (seq pb)
                     (every? :established? pa) (every? :established? pb))))
            {})))
        (.then
         (fn [up?]
           (check up? "both agents established a Noise session")
           (let [service-port (:service-port @st)
                 server (edge/forward! reg-a (:a @st)
                                       {:listen-port 0 :peer "judah"
                                        :port service-port :capability :ssh})]
             (swap! st assoc :fwd server)
             (-> (listening server)
                 (.then (fn [port]
                          (swap! st assoc :fwd-port port)
                          (println (str "  forwarder on 127.0.0.1:" port
                                        " -> judah:" service-port))
                          ;; A small interactive-sized exchange first: that is
                          ;; the shape ssh actually has, and the one this
                          ;; transport is for.
                          (tcp-exchange port (vec (map #(mod % 251) (range 64)))
                                        64 15000)))))))
        (.then
         (fn [got]
           (check (= (vec (map #(mod % 251) (range 64))) got)
                  "an interactive-sized exchange round-trips through the overlay"
                  {:got (count got)})
           (note "now a payload larger than one segment and one window")
           ;; 40 KB: past max-segment-bytes (700) and past a single window, so
           ;; this exercises segmentation, cumulative acks and flow control
           ;; rather than a single frame that happened to fit.
           (tcp-exchange (:fwd-port @st) (vec (map #(mod % 251) (range 40000)))
                         40000 40000)))
        (.then
         (fn [got]
           (let [want (vec (map #(mod % 251) (range 40000)))]
             (check (= (count want) (count got))
                    "40 KB arrived in full" {:want (count want) :got (count got)})
             (check (= want got) "…and byte for byte unchanged"
                    (when (not= want got)
                      {:first-diff (first (keep-indexed
                                           (fn [i [x y]] (when (not= x y) i))
                                           (map vector want got)))})))
           ;; Authorisation: the netmap grants :ssh on the service port only.
           ;; A forward aimed at a different port must be refused locally, and
           ;; the stream must never be opened.
           (let [server (edge/forward! reg-a (:a @st)
                                       {:listen-port 0 :peer "judah"
                                        :port 9999 :capability :ssh})]
             (swap! st assoc :bad server)
             (.then (listening server) tcp-refused?))))
        (.then
         (fn [refused?]
           (check refused? "a port the netmap does not grant is refused, deny-by-default")
           ;; And the receiving side refuses independently: hand the service a
           ;; well-formed OPEN for a port its own netmap does not grant, and it
           ;; must reset rather than connect. This is the check that matters —
           ;; the forwarder's is a courtesy, the service's is the boundary.
           (let [frame {:kind :open :stream-id 4242 :seq 0 :ack 0 :window 32768
                        :payload (vec (b/utf8-encode "ssh 9999"))}]
             (edge/on-peer-data reg-b (:b @st)
                                {:peer "asher" :payload (stream/encode frame)})
             (sleep 500))))
        (.then
         (fn [_]
           (check (nil? (get @reg-b ["asher" 4242]))
                  "the service refused an unauthorised OPEN without connecting")
           (if-not ssh-target
             (do (note "no --ssh <user@host-port> given; skipping the real ssh leg")
                 (js/Promise.resolve nil))
             (let [[user] (str/split ssh-target #"@")]
               (note (str "driving a real ssh client through the forwarder as " user))
               (ssh-through (:fwd-port @st) user)))))
        (.then
         (fn [ssh-result]
           (when ssh-result
             (check (and (= 0 (:code ssh-result))
                         (str/includes? (:out ssh-result) "kekkai-ssh-ok"))
                    "a real ssh session completed through the overlay"
                    (select-keys ssh-result [:code :err])))
           (js/clearInterval (:timer-a @st))
           (js/clearInterval (:timer-b @st))
           (.close (:fwd @st))
           (when (:bad @st) (.close (:bad @st)))
           (.close (:echo @st))
           ((:stop (:a @st)))
           ((:stop (:b @st)))
           ((:stop (:relay @st)))
           (sleep 300)))
        (.then
         (fn [_]
           (println)
           (if (seq @failures)
             (do (println (str "STREAM E2E FAILED: " (str/join ", " @failures)))
                 (set! (.-exitCode js/process) 1))
             (println "STREAM E2E PASSED"))
           (js/setTimeout #(.exit js/process (if (seq @failures) 1 0)) 200)))
        (.catch
         (fn [e]
           (println (str "\nSTREAM E2E ERROR: " e "\n" (.-stack e)))
           (set! (.-exitCode js/process) 1)
           (js/setTimeout #(.exit js/process 1) 200))))))

(apply -main (drop 3 (js->clj (.-argv js/process))))
