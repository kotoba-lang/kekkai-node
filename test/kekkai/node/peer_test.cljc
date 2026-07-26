(ns kekkai.node.peer-test
  "Peer session lifecycle, including regression tests for two bugs the real-socket
  E2E found and no single-handshake unit test could have."
  (:require [clojure.test :refer [deftest is testing]]
            [kekkai.node.peer :as peer]
            [kotoba.bytes :as b]
            [noise.core :as noise]
            #?(:clj [noise.provider.jvm :as provider]
               :cljs [noise.provider.node :as provider])))

(def suite (noise/suite (provider/ports)))
(def prologue (b/utf8-encode "kekkai-node/1 tailnet:test netmap:1"))

(defn- kp [] (noise/keypair suite))

(defn- pair
  "Two peer states facing each other. `a` initiates."
  []
  (let [ak (kp) bk (kp)
        mk (fn [self other pid init?]
             (peer/peer {:suite suite :static self :peer-key (:pub other)
                         :peer-id pid :prologue prologue :relay-region "test"
                         :candidates [] :initiator? init? :now 0
                         :policy {:rekey-timeout 5}}))]
    {:a (mk ak bk "judah" true) :b (mk bk ak "asher" false) :ak ak :bk bk}))

(defn- handshake
  "Drive a full session establishment. -> [a b]"
  [{:keys [a b]}]
  (let [[a dg1] (peer/dial a 100)
        {b :state b-send :send} (peer/on-datagram b {:bytes (:bytes dg1) :now-s 100 :now-ms 100000})
        {a :state} (peer/on-datagram a {:bytes (:bytes (first b-send)) :now-s 100 :now-ms 100000})]
    [a b]))

(deftest ik-session-establishes-both-ways
  (let [[a b] (handshake (pair))]
    (is (peer/established? a))
    (is (peer/established? b))
    (testing "and it is usable immediately, over the relay path"
      (let [[_a dg] (peer/send-data a (b/utf8-encode "hi") 101)]
        (is (= :relay (:route dg)))
        (let [{:keys [events]} (peer/on-datagram b {:bytes (:bytes dg) :now-s 101 :now-ms 101000})]
          (is (= :data (:event (first events))))
          (is (= "hi" (apply str (map char (:payload (first events)))))))))))

(deftest a-retry-does-not-invalidate-the-earlier-attempt
  (testing "REGRESSION (found by the E2E): when the retry interval is shorter
            than the round trip, the response to attempt N arrives after attempt
            N+1 was sent. Replacing the pending handshake made that valid
            response fail authentication — and the log said 'authentication
            failed', which reads like an attack rather than a race."
    (let [{:keys [a b]} (pair)
          [a dg1] (peer/dial a 100)                     ; attempt 1
          [a _dg2] (peer/dial a 101)                    ; attempt 2, before any reply
          [a _dg3] (peer/dial a 102)                    ; attempt 3
          ;; the peer answers attempt 1
          {b :state b-send :send} (peer/on-datagram b {:bytes (:bytes dg1) :now-s 100 :now-ms 100000})
          {a :state :keys [events]} (peer/on-datagram a {:bytes (:bytes (first b-send))
                                                        :now-s 103 :now-ms 103000})]
      (is (peer/established? a) "the late-but-valid response still completes")
      (is (= :established (:event (first events))))
      (is (= 1 (:attempt (first events))) "and it is recognized as attempt 1")
      (is (peer/established? b)))))

(deftest a-stale-response-does-not-replace-a-newer-session
  (testing "REGRESSION (found by the E2E): responses can arrive out of order. If
            an older attempt's response is adopted after a newer session is
            already in use, the two ends end up on different sessions and every
            subsequent frame fails to authenticate."
    (let [{:keys [a b]} (pair)
          [a dg1] (peer/dial a 100)
          [a dg2] (peer/dial a 101)
          ;; peer answers BOTH attempts (it is stateless about which it answered)
          {b1 :state b1-send :send} (peer/on-datagram b {:bytes (:bytes dg1) :now-s 100 :now-ms 100000})
          {b2-send :send} (peer/on-datagram b1 {:bytes (:bytes dg2) :now-s 101 :now-ms 101000})
          ;; the newer response lands first
          {a :state} (peer/on-datagram a {:bytes (:bytes (first b2-send)) :now-s 102 :now-ms 102000})
          session-before (:session a)
          {a :state :keys [events]} (peer/on-datagram a {:bytes (:bytes (first b1-send))
                                                         :now-s 103 :now-ms 103000})]
      (is (= :stale-handshake-resp (:event (first events))))
      (is (identical? session-before (:session a)) "the newer session is kept"))))

(deftest a-peer-presenting-the-wrong-static-key-is-refused
  (testing "an authorized node must not be able to answer for another node's id:
            the handshake authenticates a key, and it has to be the key the
            netmap assigned to this peer"
    (let [{:keys [a bk]} (pair)
          impostor (peer/peer {:suite suite :static (kp) :peer-key (:pub bk)
                               :peer-id "asher" :prologue prologue :candidates []
                               :initiator? false :now 0})
          ;; the impostor holds a valid key pair, just not the expected one
          victim (peer/peer {:suite suite :static (kp) :peer-key (:pub (kp))
                             :peer-id "judah" :prologue prologue :candidates []
                             :initiator? false :now 0})
          [_ dg] (peer/dial a 100)
          {:keys [events]} (peer/on-datagram victim {:bytes (:bytes dg) :now-s 100 :now-ms 100000})]
      (is (contains? #{:wrong-static-key :handshake-failed} (:event (first events))))
      (is (not (peer/established? impostor))))))

(deftest a-prologue-mismatch-fails-the-handshake
  (testing "a peer on a different netmap version cannot establish — it would
            otherwise operate under stale ACLs"
    (let [ak (kp) bk (kp)
          a (peer/peer {:suite suite :static ak :peer-key (:pub bk) :peer-id "b"
                        :prologue (b/utf8-encode "kekkai-node/1 tailnet:test netmap:1")
                        :candidates [] :initiator? true :now 0})
          b (peer/peer {:suite suite :static bk :peer-key (:pub ak) :peer-id "a"
                        :prologue (b/utf8-encode "kekkai-node/1 tailnet:test netmap:2")
                        :candidates [] :initiator? false :now 0})
          [_ dg] (peer/dial a 100)
          {:keys [events]} (peer/on-datagram b {:bytes (:bytes dg) :now-s 100 :now-ms 100000})]
      (is (= :handshake-failed (:event (first events)))))))

(deftest disco-frames-are-authenticated-and-teach-the-path
  (let [[a b] (handshake (pair))
        endpoint {:kind :reflexive :host "203.0.113.9" :port 41641}]
    (testing "a ping that decrypts from an address proves that inbound path works"
      (let [[_a dg] (peer/send-ping a endpoint "tx-1" 101)
            {b :state :keys [events]} (peer/on-datagram b {:bytes (:bytes dg) :now-s 101
                                                           :now-ms 101000
                                                           :from-endpoint endpoint})]
        (is (= :direct (:route dg)))
        (is (= :ping (:event (first events))))
        (is (= "tx-1" (:tx-id (first events))))
        (is (= :live (get-in b [:disco :paths "203.0.113.9:41641" :state]))
            "the path is marked live by the authenticated frame, not by its arrival")))
    (testing "a forged disco frame teaches nothing: it never decrypts, so it
              cannot move a peer's active path"
      (let [[_ dg] (peer/send-ping a endpoint "tx-2" 101)
            forged (update (vec (:bytes dg)) 20 bit-xor 0x01)
            {b' :state :keys [events]} (peer/on-datagram b {:bytes forged :now-s 101
                                                            :now-ms 101000
                                                            :from-endpoint {:kind :reflexive
                                                                            :host "198.51.100.1"
                                                                            :port 9}}) ]
        (is (= :dropped (:event (first events))))
        (is (nil? (get-in b' [:paths "198.51.100.1:9"])))))))

(deftest call-me-maybe-carries-candidates
  (let [[a b] (handshake (pair))
        cands ["192.168.1.10:41641" "203.0.113.9:41641"]
        [_ dg] (peer/send-call-me-maybe a cands 101)
        {:keys [events]} (peer/on-datagram b {:bytes (:bytes dg) :now-s 101 :now-ms 101000})]
    (is (= :relay (:route dg)) "signalling goes over the relay: no direct path exists yet")
    (is (= :call-me-maybe (:event (first events))))
    (is (= cands (:candidates (first events))))))

(deftest tick-dials-only-on-the-initiator-side
  (let [{:keys [a b]} (pair)
        out-a (peer/tick a {:now-s 100 :now-ms 100000 :local-candidates []})
        out-b (peer/tick b {:now-s 100 :now-ms 100000 :local-candidates []})]
    (is (= :dialing (:event (first (:events out-a)))))
    (is (empty? (:events out-b)) "the responder waits — two dialers collide")
    (is (peer/should-initiate? "asher" "judah"))
    (is (not (peer/should-initiate? "judah" "asher")))))

(deftest an-expired-session-is-dropped-and-redialled
  (let [[a _] (handshake (pair))
        out (peer/tick a {:now-s (+ 100 181) :now-ms 281000 :local-candidates []})]
    (is (= :expired (:event (first (:events out)))))
    (is (not (peer/established? (:state out))))
    (testing "and the next tick dials again"
      (let [out2 (peer/tick (:state out) {:now-s 282 :now-ms 282000 :local-candidates []})]
        (is (= :dialing (:event (first (:events out2)))))))))

(deftest framing-rejects-hostile-input
  (is (= :short-frame (:error (peer/decode-frame [1]))))
  (is (= :bad-magic (:error (peer/decode-frame [0 1 0x30]))))
  (is (= :unknown-frame-type (:error (peer/decode-frame [peer/magic 1 0x99])))))
