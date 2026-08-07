(ns kekkai.node.stream-test
  "The reliable stream, driven against a network that loses, reorders and
  duplicates.

  A transport tested only on a perfect link is tested on the one condition it
  was not written for. Every property here is stated as 'the bytes that come
  out equal the bytes that went in', because that is the only claim a forwarder
  actually needs and every other invariant is instrumental to it."
  (:require [clojure.test :refer [deftest is testing]]
            [kekkai.node.stream :as s]))

;; ── a deterministic unreliable network ──────────────────────────────────────
;;
;; Deterministic on purpose: a flaky transport test that fails one run in fifty
;; teaches nothing, and `Math/random` would make the failing case unreproducible.
;; A small LCG gives the same loss pattern every run, and the seed is the knob.

(defn- lcg
  "Park–Miller. The multiplier matters for a reason that is not about
  randomness: `48271 * 2^31` is under 2^53, so this is exact in a double and
  the JVM and ClojureScript produce the **same** sequence.

  The first version used the glibc constants (multiplier 1103515245), whose
  product overflows 2^53 — so it stayed exact on the JVM's longs and silently
  lost precision in ClojureScript. Both platforms ran a deterministic test and
  they ran *different* deterministic tests. That is how a real FIN-retransmit
  bug passed under nbb for an afternoon and failed the first time the JVM suite
  ran: a harness that diverges by platform gives two green runs and one truth."
  [seed]
  (mod (* 48271 seed) 2147483647))

(defn- chance
  "-> [hit? next-seed]. `pct` in 0..100."
  [seed pct]
  (let [seed (lcg seed)]
    [(< (mod seed 100) pct) seed]))

(defn- link
  "A one-way link with loss, reordering and duplication.

  Returns `{:queue … :seed …}`. Frames are delivered on `deliver` in the order
  the queue holds them, which reordering permutes."
  [seed]
  {:queue [] :seed seed :loss 0 :reorder 0 :dup 0})

(defn- put [l frames]
  (reduce
   (fn [l f]
     (let [[lost? seed] (chance (:seed l) (:loss l))
           l (assoc l :seed seed)]
       (if lost?
         l
         (let [[dup? seed] (chance (:seed l) (:dup l))
               [swap? seed] (chance seed (:reorder l))
               l (assoc l :seed seed)
               q (cond-> (conj (:queue l) f) dup? (conj f))
               q (if (and swap? (>= (count q) 2))
                   ;; move the newest frame in front of the one before it
                   (let [n (count q)]
                     (assoc q (- n 1) (nth q (- n 2)) (- n 2) (nth q (- n 1))))
                   q)]
           (assoc l :queue q)))))
   l frames))

(defn- drain [l] [(assoc l :queue []) (:queue l)])

;; ── a two-endpoint harness ──────────────────────────────────────────────────

(defn- new-pair
  "An initiator and a responder, already open, plus two links."
  [{:keys [loss reorder dup seed] :or {loss 0 reorder 0 dup 0 seed 7}}]
  (let [{a :state open-frames :frames} (s/initiator {:stream-id 1 :capability :ssh
                                                     :port 22 :now-ms 0})
        {:keys [state frames]} (s/responder {:frame (first open-frames) :now-ms 0})
        {a :state} (s/on-frame a (first frames) 0)]
    {:a a :b state
     :a->b (assoc (link seed) :loss loss :reorder reorder :dup dup)
     :b->a (assoc (link (+ seed 1000)) :loss loss :reorder reorder :dup dup)
     :out-a [] :out-b []}))

(defn- pump
  "Run the pair for `steps` ticks, moving frames across both links.

  `now-ms` advances by 100 per step so retransmission timers actually expire —
  a harness with a frozen clock cannot observe recovery, which is the whole
  point of the loss tests."
  [h steps]
  (loop [h h step 0]
    (if (>= step steps)
      h
      (let [now (* step 100)
            ;; deliver everything queued in both directions
            [ab fa] (drain (:a->b h))
            [ba fb] (drain (:b->a h))
            [b out-b to-a] (reduce (fn [[b out to] f]
                                     (let [r (s/on-frame b f now)]
                                       [(:state r) (into out (:delivered r))
                                        (into to (:frames r))]))
                                   [(:b h) [] []] fa)
            [a out-a to-b] (reduce (fn [[a out to] f]
                                     (let [r (s/on-frame a f now)]
                                       [(:state r) (into out (:delivered r))
                                        (into to (:frames r))]))
                                   [(:a h) [] []] fb)
            ta (s/tick a now)
            tb (s/tick b now)]
        (recur (-> h
                   (assoc :a (:state ta) :b (:state tb))
                   (assoc :a->b (put ab (into to-b (:frames ta))))
                   (assoc :b->a (put ba (into to-a (:frames tb))))
                   (update :out-a into out-a)
                   (update :out-b into out-b))
               (inc step))))))

(defn- bytes-of [n] (vec (map #(mod % 251) (range n))))

;; ── the frame codec ─────────────────────────────────────────────────────────

(deftest a-frame-round-trips-through-its-own-codec
  (doseq [kind [:open :open-ok :data :ack :fin :rst]]
    (let [f {:kind kind :stream-id 4294967295 :seq 123456 :ack 654321
             :window 65535 :payload [1 2 3 250]}
          decoded (s/decode (s/encode f))]
      (is (= f decoded) (str kind)))))

(deftest a-frame-that-cannot-be-fully-accounted-for-decodes-to-nil
  (testing "short of a header"
    (is (nil? (s/decode (vec (repeat 15 0))))))
  (testing "wrong version"
    (is (nil? (s/decode (assoc (s/encode {:kind :data :stream-id 1}) 0 99)))))
  (testing "unknown kind — guessing is how a decoder becomes an attack surface"
    (is (nil? (s/decode (assoc (s/encode {:kind :data :stream-id 1}) 1 77))))))

(deftest the-header-is-exactly-sixteen-bytes
  (is (= 16 (count (s/encode {:kind :ack :stream-id 1}))))
  (is (= 20 (count (s/encode {:kind :data :stream-id 1 :payload [1 2 3 4]})))))

;; ── the happy path ──────────────────────────────────────────────────────────

(deftest an-open-names-the-service-and-the-responder-reads-it-back
  (let [{:keys [frames]} (s/initiator {:stream-id 9 :capability :ssh :port 22
                                       :now-ms 0})
        {:keys [request]} (s/responder {:frame (first frames) :now-ms 0})]
    (is (= {:capability :ssh :port 22} request))
    (testing "so the edge can ask netmap/permitted? about the real port"
      (is (= 22 (:port request))))))

(deftest a-malformed-open-is-refused-rather-than-guessed-at
  (let [{:keys [refused frames]} (s/responder
                                  {:frame {:stream-id 3 :payload (vec (map int "garbage"))}
                                   :now-ms 0})]
    (is (= :malformed-open refused))
    (is (= :rst (:kind (first frames))))))

(deftest bytes-written-before-the-open-completes-are-not-stranded
  (testing "a forwarder's local client writes immediately, so the first bytes of
            every connection are queued while the stream is still :opening —
            the OPEN-OK has to flush them or the stream opens and transfers
            nothing (found by the socket E2E, which is exactly the case a
            state-machine test driven from an already-open pair cannot reach)"
    (let [{a0 :state open-frames :frames} (s/initiator {:stream-id 1 :capability :ssh
                                                        :port 22 :now-ms 0})
          a a0
          ;; written before OPEN-OK comes back
          {a :state early-frames :frames} (s/send-bytes a (bytes-of 300) 0)
          _ (is (empty? early-frames) "nothing may be sent while opening")
          {b :state ok-frames :frames} (s/responder {:frame (first open-frames)
                                                     :now-ms 0})
          {a :state frames :frames} (s/on-frame a (first ok-frames) 1)]
      (is (seq frames) "the OPEN-OK flushed what was queued")
      (let [r (reduce (fn [acc f]
                        (let [x (s/on-frame (:state acc) f 2)]
                          {:state (:state x) :out (into (:out acc) (:delivered x))}))
                      {:state b :out []} frames)]
        (is (= (bytes-of 300) (:out r)))))))

(deftest bytes-cross-a-perfect-link-unchanged
  (let [payload (bytes-of 5000)
        h (new-pair {})
        {:keys [state frames]} (s/send-bytes (:a h) payload 0)
        h (-> h (assoc :a state) (update :a->b put frames))
        h (pump h 40)]
    (is (= payload (:out-b h)))
    (is (empty? (:out-a h)) "nothing was invented in the reverse direction")))

(deftest both-directions-are-independently-sequenced
  (let [up (bytes-of 1500) down (bytes-of 900)
        h (new-pair {})
        ra (s/send-bytes (:a h) up 0)
        rb (s/send-bytes (:b h) down 0)
        h (-> h (assoc :a (:state ra) :b (:state rb))
              (update :a->b put (:frames ra))
              (update :b->a put (:frames rb)))
        h (pump h 40)]
    (is (= up (:out-b h)))
    (is (= down (:out-a h)))))

;; ── the conditions it exists for ────────────────────────────────────────────

(deftest bytes-survive-a-lossy-link
  (doseq [loss [10 25 40]]
    (let [payload (bytes-of 4000)
          h (new-pair {:loss loss :seed (+ 11 loss)})
          {:keys [state frames]} (s/send-bytes (:a h) payload 0)
          h (-> h (assoc :a state) (update :a->b put frames))
          h (pump h 200)]
      (is (= payload (:out-b h)) (str loss "% loss")))))

(deftest bytes-survive-reordering
  (let [payload (bytes-of 4000)
        h (new-pair {:reorder 50 :seed 23})
        {:keys [state frames]} (s/send-bytes (:a h) payload 0)
        h (-> h (assoc :a state) (update :a->b put frames))
        h (pump h 120)]
    (is (= payload (:out-b h))
        "out-of-order segments are buffered, not discarded")))

(deftest duplicates-are-delivered-once
  (let [payload (bytes-of 3000)
        h (new-pair {:dup 40 :seed 31})
        {:keys [state frames]} (s/send-bytes (:a h) payload 0)
        h (-> h (assoc :a state) (update :a->b put frames))
        h (pump h 120)]
    (is (= payload (:out-b h))
        "a retransmit of already-delivered bytes must not appear twice")))

(deftest bytes-survive-all-three-at-once
  (let [payload (bytes-of 6000)
        h (new-pair {:loss 20 :reorder 30 :dup 20 :seed 97})
        {:keys [state frames]} (s/send-bytes (:a h) payload 0)
        h (-> h (assoc :a state) (update :a->b put frames))
        h (pump h 400)]
    (is (= payload (:out-b h)))))

;; ── close ───────────────────────────────────────────────────────────────────

(deftest a-close-flushes-before-it-finishes
  (testing "a FIN that overtook queued bytes would truncate the stream, and the
            receiver cannot tell a truncation from a clean close"
    (let [payload (bytes-of 3000)
          h (new-pair {})
          r1 (s/send-bytes (:a h) payload 0)
          r2 (s/close (:state r1) 0)
          h (-> h (assoc :a (:state r2))
                (update :a->b put (into (:frames r1) (:frames r2))))
          h (pump h 60)]
      (is (= payload (:out-b h)))
      (is (some? (:recv-fin (:b h))) "and the close was seen"))))

(deftest the-ack-for-the-last-data-byte-does-not-retire-the-fin
  (testing "the FIN occupies sequence number N, so only an ack ABOVE N retires
            it. Retiring it at ack == N means the ack for the last data byte
            drops it from the retransmit queue, and a FIN lost in flight is
            never sent again — the peer waits for an end that never comes"
    (let [{a :state open-frames :frames} (s/initiator {:stream-id 1 :capability :ssh
                                                       :port 22 :now-ms 0})
          {b :state ok :frames} (s/responder {:frame (first open-frames) :now-ms 0})
          {a :state} (s/on-frame a (first ok) 0)
          {a :state} (s/send-bytes a (bytes-of 10) 0)
          {a :state} (s/close a 0)
          fin-seq (:fin-seq a)
          ;; the peer acks every data byte but has NOT seen the FIN
          {a :state} (s/on-frame a {:kind :ack :stream-id 1 :seq 0 :ack fin-seq
                                    :window 60000 :payload []} 0)]
      (is (= 10 fin-seq))
      (is (= 1 (count (:unacked a)))
          "the FIN is still outstanding and must stay retransmittable")
      (is (:fin? (first (:unacked a))))
      (testing "and it is retransmitted as a FIN, not as an empty DATA"
        (is (= :fin (:kind (first (:frames (s/tick a 1000))))))))))

(deftest a-lost-fin-is-recovered
  (testing "the FIN occupies a sequence number, so it is retransmitted and
            acknowledged like any other segment"
    (let [h (new-pair {:loss 30 :seed 57})
          r1 (s/send-bytes (:a h) (bytes-of 800) 0)
          r2 (s/close (:state r1) 0)
          h (-> h (assoc :a (:state r2))
                (update :a->b put (into (:frames r1) (:frames r2))))
          h (pump h 300)]
      (is (= (bytes-of 800) (:out-b h)))
      (is (some? (:recv-fin (:b h))))
      (testing "the sender learned its FIN arrived: the cumulative ack passed
                the FIN's sequence number and retired it from the retransmit
                queue. NOT closed? — only one direction has finished, and a
                stream whose peer may still send is not closed"
        (is (empty? (:unacked (:a h))))
        (is (> (:send-una (:a h)) (:fin-seq (:a h))))
        (is (not (s/closed? (:a h))))))))

(deftest half-close-lets-the-other-direction-keep-going
  (testing "ssh and every request/response protocol send EOF one way and keep
            reading the other"
    (let [h (new-pair {})
          ra (s/close (:a h) 0)
          h (-> h (assoc :a (:state ra)) (update :a->b put (:frames ra)))
          h (pump h 10)
          rb (s/send-bytes (:b h) (bytes-of 500) 1000)
          h (-> h (assoc :b (:state rb)) (update :b->a put (:frames rb)))
          h (pump h 30)]
      (is (= (bytes-of 500) (:out-a h))
          "the responder could still send after the initiator's FIN"))))

;; ── the bounds ──────────────────────────────────────────────────────────────

(deftest a-lost-open-is-retransmitted-and-eventually-gives-up
  (let [{:keys [state]} (s/initiator {:stream-id 1 :capability :ssh :port 22
                                      :now-ms 0})]
    (testing "retransmitted while opening"
      (is (= :open (:kind (first (:frames (s/tick state 300)))))))
    (testing "and reset rather than waiting forever — a hang is worse than a
              failure, because only one of them can be acted on"
      (let [{:keys [state frames events]} (s/tick state 31000)]
        (is (= :reset (:phase state)))
        (is (= :open-timeout (:reset-reason state)))
        (is (= :rst (:kind (first frames))))
        (is (= :open-timeout (:reason (first events))))))))

(deftest a-dead-peer-resets-instead-of-retrying-forever
  (let [h (new-pair {})
        {:keys [state frames]} (s/send-bytes (:a h) (bytes-of 100) 0)]
    (is (seq frames))
    (let [{:keys [state events]} (s/tick state 31000)]
      (is (= :reset (:phase state)))
      (is (= :retransmit-timeout (:reset-reason state)))
      (is (= :retransmit-timeout (:reason (first events)))))))

(deftest the-sender-respects-the-peers-window
  (testing "a receiver that advertises a small window is not overrun"
    (let [h (new-pair {})
          a (assoc (:a h) :peer-window 1000)
          {:keys [state frames]} (s/send-bytes a (bytes-of 10000) 0)]
      (is (<= (reduce + 0 (map #(count (:payload %)) frames)) 1000))
      (is (= 9000 (count (:pending state))) "the rest waits rather than being dropped"))))

(deftest a-segment-past-the-reorder-bound-is-dropped-not-buffered
  (testing "otherwise a peer that withholds one segment pins memory forever"
    (let [h (new-pair {})
          far (+ s/max-reorder-bytes 5000)
          r (s/on-frame (:b h) {:kind :data :stream-id 1 :seq far :ack 0
                                :window 60000 :payload [1 2 3]} 0)]
      (is (empty? (:reorder (:state r))))
      (is (empty? (:delivered r))))))

(deftest a-reset-names-its-reason-on-the-wire
  (testing "'the connection closed' is not an operable message"
    (let [h (new-pair {})
          rst (s/refuse 1 :edge-not-authorized)
          r (s/on-frame (:a h) rst 0)]
      (is (= :reset (:phase (:state r))))
      (is (= :edge-not-authorized (:reset-reason (:state r))))
      (is (= :edge-not-authorized (:reason (first (:events r))))))))

(deftest a-duplicate-open-is-answered-idempotently
  (testing "a replayed OPEN must not reset an established stream"
    (let [{:keys [frames]} (s/initiator {:stream-id 1 :capability :ssh :port 22
                                         :now-ms 0})
          {b :state} (s/responder {:frame (first frames) :now-ms 0})
          r (s/on-frame b (first frames) 10)]
      (is (= :open-ok (:kind (first (:frames r)))))
      (is (= :open (:phase (:state r)))))))

(deftest a-retransmitted-segment-below-the-prefix-does-not-move-it-backwards
  (let [h (new-pair {})
        r1 (s/on-frame (:b h) {:kind :data :stream-id 1 :seq 0 :ack 0
                               :window 60000 :payload [1 2 3 4]} 0)
        r2 (s/on-frame (:state r1) {:kind :data :stream-id 1 :seq 0 :ack 0
                                    :window 60000 :payload [1 2 3 4]} 0)]
    (is (= [1 2 3 4] (:delivered r1)))
    (is (empty? (:delivered r2)) "delivered once")
    (is (= 4 (:recv-next (:state r2))) "and the prefix did not regress")
    (testing "a duplicate is still acked — silence would leave the sender
              retransmitting until it gave up"
      (is (= :ack (:kind (first (:frames r2))))))))

(deftest an-overlapping-retransmit-keeps-only-the-new-part
  (let [h (new-pair {})
        r1 (s/on-frame (:b h) {:kind :data :stream-id 1 :seq 0 :ack 0
                               :window 60000 :payload [1 2 3 4]} 0)
        ;; seq 2 overlaps bytes 2..3 which are already delivered
        r2 (s/on-frame (:state r1) {:kind :data :stream-id 1 :seq 2 :ack 0
                                    :window 60000 :payload [3 4 5 6]} 0)]
    (is (= [5 6] (:delivered r2)))
    (is (= 6 (:recv-next (:state r2))))))
