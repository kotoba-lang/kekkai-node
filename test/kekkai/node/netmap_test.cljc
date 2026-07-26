(ns kekkai.node.netmap-test
  (:require [clojure.test :refer [deftest is testing]]
            [kekkai.node.netmap :as netmap]))

(def nm
  {:netmap/version 42
   :netmap/tailnet "kekkai.example"
   :netmap/self {:node/id "asher" :node/key "aa" :node/overlay-ip "100.64.0.1"}
   :netmap/peers
   [{:node/id "judah" :node/key "bb" :node/overlay-ip "100.64.0.2"
     :node/status "authorized" :node/expires-at 2000
     :node/endpoints [{:kind :reflexive :host "203.0.113.9" :port 41641}]}
    {:node/id "zebulun" :node/key "cc" :node/overlay-ip "100.64.0.3"
     :node/status "authorized" :node/expires-at 2000}
    {:node/id "eve" :node/key "dd" :node/overlay-ip "100.64.0.4"
     :node/status "pending"}
    {:node/id "stale" :node/key "ee" :node/overlay-ip "100.64.0.5"
     :node/status "authorized" :node/expires-at 500}]
   :netmap/edges
   [{:edge/from "asher" :edge/to "judah"
     :edge/capabilities [:overlay :ssh :private-http]
     :edge/ports [22 443]}
    {:edge/from "asher" :edge/to "zebulun" :edge/capabilities [:overlay]}
    {:edge/from "asher" :edge/to "eve" :edge/capabilities [:overlay]}
    {:edge/from "asher" :edge/to "stale" :edge/capabilities [:overlay]}]
   :netmap/relays [{:relay/name "jp-tyo-1" :relay/region "jp"
                    :relay/host "relay.example" :relay/port 41642 :relay/key "ff"}]})

(deftest validation
  (is (netmap/usable? nm))
  (is (not (netmap/usable? (dissoc nm :netmap/version))))
  (is (not (netmap/usable? (dissoc nm :netmap/tailnet))))
  (testing "a peer without a key is a problem, not a peer to be skipped silently"
    (is (= [{:problem :peer-missing-key :peer "judah"}]
           (netmap/validate (assoc-in nm [:netmap/peers 0 :node/key] ""))))))

(deftest deny-by-default
  (testing "no edge means denied even for an authorized peer"
    (is (not (netmap/reachable? nm "asher" "judah" :sql)))
    (is (netmap/reachable? nm "asher" "judah" :ssh))
    (is (not (netmap/reachable? nm "asher" "unknown-node" :overlay))))
  (testing "edges are directional"
    (is (not (netmap/reachable? nm "judah" "asher" :overlay)))))

(deftest admission-and-expiry
  (is (netmap/authorized? (first (:netmap/peers nm)) 1000))
  (testing "a pending peer is not admitted"
    (is (not (netmap/authorized? (nth (:netmap/peers nm) 2) 1000))))
  (testing "an authorized peer with an expired key is not admitted — being in the
            netmap is never, on its own, sufficient"
    (is (not (netmap/authorized? (nth (:netmap/peers nm) 3) 1000)))))

(deftest dialable-folds-admission-and-edges
  (let [ids (mapv (comp :node/id :peer) (netmap/dialable nm :overlay 1000))]
    (is (= ["judah" "zebulun"] ids))
    (testing "the ssh capability is granted to judah only"
      (is (= ["judah"] (mapv (comp :node/id :peer) (netmap/dialable nm :ssh 1000)))))))

(deftest application-permission-is-direction-and-port-aware
  (is (netmap/permitted? nm "asher" "judah" :private-http 443))
  (is (not (netmap/permitted? nm "asher" "judah" :private-http 80)))
  (is (not (netmap/permitted? nm "judah" "asher" :private-http 443))))

(deftest inbound-only-edges-still-create-an-authenticated-session
  (let [inbound (-> nm
                    (assoc :netmap/peers
                           [{:node/id "judah" :node/key "bb"
                             :node/status "authorized" :node/expires-at 2000}])
                    (assoc :netmap/edges
                           [{:edge/from "judah" :edge/to "asher"
                             :edge/capabilities [:overlay :private-http]
                             :edge/ports [443]}]))
        peer (first (netmap/sessionable inbound 1000))]
    (is (= "judah" (get-in peer [:peer :node/id])))
    (is (= #{} (:outgoing-capabilities peer)))
    (is (= #{:overlay :private-http} (:incoming-capabilities peer)))
    (is (empty? (netmap/dialable inbound :overlay 1000)))))

(deftest denials-are-explainable
  (let [by-peer (into {} (map (juxt :peer :denied)) (netmap/denials nm :overlay 1000))]
    (is (= :status-pending (get by-peer "eve")))
    (is (= :key-expired (get by-peer "stale")))
    (is (nil? (get by-peer "judah"))))
  (testing "admission is reported before the edge grant: a pending or expired peer
            is denied for being un-admitted, not for lacking the capability —
            fixing the edge would not have helped"
    (let [by-peer (into {} (map (juxt :peer :denied)) (netmap/denials nm :sql 1000))]
      (is (= {"judah" :no-edge-grant "zebulun" :no-edge-grant
              "eve" :status-pending "stale" :key-expired}
             by-peer))
      (testing "and the denial says what *was* granted, so the fix is obvious"
        (is (= #{:ssh :overlay :private-http}
               (:granted (first (filter #(= "judah" (:peer %))
                                        (netmap/denials nm :sql 1000))))))))))

(deftest prologue-binds-tailnet-and-version
  (is (= "kekkai-node/1 tailnet:kekkai.example netmap:42" (netmap/prologue-string nm)))
  (testing "a different netmap version yields a different prologue, which is what
            makes a stale peer fail the handshake instead of using stale ACLs"
    (is (not= (netmap/prologue-string nm)
              (netmap/prologue-string (assoc nm :netmap/version 43))))))
