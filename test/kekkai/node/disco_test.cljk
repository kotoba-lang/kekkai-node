(ns kekkai.node.disco-test
  "Path discovery with a frozen clock — every transition is a value, so none of
  this needs a socket or a sleep."
  (:require [clojure.test :refer [deftest is testing]]
            [kekkai.node.disco :as disco]
            [kekkai.node.endpoint :as ep]))

(def cands [{:kind :reflexive :host "203.0.113.9" :port 41641}
            {:kind :local :host "192.168.1.10" :port 41641}])

(defn- st0 [] (disco/new-peer {:peer-id "judah" :relay-region "jp"
                               :candidates cands :now 0}))

(deftest starts-on-the-relay
  (testing "connectivity first: a peer is reachable before any discovery happens"
    (is (= :relay (:active (st0))))
    (is (= :relay (disco/best-path (st0) 0)))))

(deftest probes-every-candidate-then-gives-up
  (let [st (st0)
        {:keys [pings]} (disco/advice st 0)]
    (is (= 2 (count pings)))
    (is (= #{"203.0.113.9:41641" "192.168.1.10:41641"}
           (set (map (comp ep/ekey :endpoint) pings))))
    (testing "each probe has a distinct transaction id so a pong can be matched"
      (is (= 2 (count (distinct (map :tx-id pings))))))
    (testing "after max attempts with no pong the candidate is dead and stops
              consuming bursts"
      (let [st (loop [st st t 0 n 0]
                 (if (= n 5)
                   st
                   (let [{:keys [pings state]} (disco/advice st t)]
                     (recur (disco/record-pings state pings t) (+ t 1600) (inc n)))))]
        (is (= :relay (disco/best-path st 8000)))
        (is (every? #(= :dead (:state %)) (vals (:paths st))))
        (is (empty? (:pings (disco/advice st 8000))))))))

(deftest a-pong-upgrades-to-direct
  (let [st (st0)
        {:keys [pings state]} (disco/advice st 0)
        st (disco/record-pings state pings 0)
        st (disco/on-pong st (first cands) 30 {:tx-id (:tx-id (first pings))})
        best (disco/best-path st 30)]
    (is (= "203.0.113.9:41641" best))
    (is (= 30 (get-in st [:paths best :latency-ms])))
    (testing "and the agent is told to switch"
      (is (= best (:switch-to (disco/advice st 30)))))))

(deftest lowest-latency-wins-with-hysteresis
  (let [st (st0)
        {:keys [pings state]} (disco/advice st 0)
        st (disco/record-pings state pings 0)
        st (-> st
               (disco/on-pong (first cands) 50 {})     ; reflexive: 50ms
               (disco/on-pong (second cands) 8 {}))    ; LAN: 8ms
        st (disco/activate st (disco/best-path st 50))]
    (is (= "192.168.1.10:41641" (:active st)) "the LAN path wins")
    (testing "a marginally better candidate does not cause a flap"
      (let [st (disco/on-pong st (first cands) 60 {})
            st (assoc-in st [:paths "203.0.113.9:41641" :latency-ms] 8)]
        (is (= "192.168.1.10:41641" (disco/best-path st 60)))))))

(deftest a-dead-path-falls-back-to-the-relay-immediately
  (let [st (st0)
        {:keys [pings state]} (disco/advice st 0)
        st (disco/record-pings state pings 0)
        st (disco/on-pong st (first cands) 20 {})
        st (disco/activate st (disco/best-path st 20))]
    (is (not= :relay (:active st)))
    (testing "no pong for path-death-ms and we are back on the relay — no
              hysteresis here, because packets are being lost right now"
      (let [st (disco/expire-dead st 16000)]
        (is (= :relay (disco/best-path st 16000)))
        (is (= :relay (:switch-to (disco/advice st 16000))))))))

(deftest hole-punch-uses-a-fixed-shared-schedule
  (testing "both sides fire on the same offsets from the punch start, which is
            the entire mechanism of a simultaneous open"
    (let [st (disco/begin-punch (st0) 1000)
          at (fn [st t] (let [{:keys [pings state]} (disco/advice st t)]
                          [(disco/record-pings state pings t) (count pings)]))
          [st n0] (at st 1000)
          [st n1] (at st 1050)     ; between offsets 0 and 100: nothing new
          [st n2] (at st 1100)     ; offset 100 due
          [_ n3] (at st 1400)]     ; offset 300 due
      (is (= 2 n0) "both candidates pinged at offset 0")
      (is (zero? n1))
      (is (= 2 n2))
      (is (= 2 n3)))))

(deftest call-me-maybe-signals-while-relayed-and-arms-the-punch
  (let [st (st0)]
    (is (disco/call-me-maybe-due? st 6000) "no direct path -> keep signalling")
    (let [st (disco/record-call-me-maybe st 6000)]
      (is (not (disco/call-me-maybe-due? st 6001)) "but not every tick")
      (is (some? (:punch st)) "sending our candidates arms the punch")))
  (testing "receiving the peer's candidates adds them and arms the punch"
    (let [st (disco/on-call-me-maybe (st0) [{:kind :reflexive :host "198.51.100.7" :port 5000}] 100)]
      (is (contains? (:paths st) "198.51.100.7:5000"))
      (is (= 100 (get-in st [:punch :started-at]))))))

(deftest re-announced-dead-candidates-do-not-restart-probing
  (testing "a peer that spams call-me-maybe must not keep us probing an address
            we already proved dead"
    (let [st (-> (st0)
                 (assoc-in [:paths "192.168.1.10:41641" :state] :dead)
                 (assoc-in [:paths "192.168.1.10:41641" :attempts] 4)
                 (disco/add-candidates cands))]
      (is (= :dead (get-in st [:paths "192.168.1.10:41641" :state]))))))

(deftest path-report-tells-you-if-punching-works
  (let [st (st0)
        {:keys [pings state]} (disco/advice st 0)
        st (-> (disco/record-pings state pings 0)
               (disco/on-pong (first cands) 25 {}))
        st (disco/activate st (disco/best-path st 25))
        [r] (disco/path-report [st] 25)]
    (is (= :direct (:via r)))
    (is (= 25 (:best-latency-ms r)))
    (is (= 2 (:direct-candidates r)))
    (testing "a relayed peer reports as relayed, with its region"
      (let [[r] (disco/path-report [(st0)] 0)]
        (is (= :relay (:via r)))
        (is (= "jp" (:relay-region r)))
        (is (nil? (:best-latency-ms r)))))))
