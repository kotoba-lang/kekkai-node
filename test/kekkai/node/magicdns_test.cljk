(ns kekkai.node.magicdns-test
  (:require [clojure.test :refer [deftest is testing]]
            [kekkai.node.magicdns :as magicdns]
            [nameserver.resolver :as resolver]))

(def nm
  {:netmap/version 7
   :netmap/tailnet "kekkai.example"
   :netmap/self {:node/id "asher" :node/key "aaaaaaaabbbb" :node/overlay-ip "100.64.0.1"}
   :netmap/peers [{:node/id "judah" :node/key "bbbbbbbbcccc" :node/overlay-ip "100.64.0.2"
                   :node/overlay-ip6 "fd7a:115c::2" :node/status "authorized"}]})

(def r (magicdns/netmap-resolver (constantly nm) "kekkai.example"))

(defn- q [name type] (resolver/-resolve r name type "IN"))

(deftest resolves-node-names-to-overlay-addresses
  (let [{:keys [status answers aa?]} (q "judah.kekkai.example." "A")]
    (is (= :ok status))
    (is aa?)
    (is (= "100.64.0.2" (get-in (first answers) [:zone/rdata :zone/address]))))
  (testing "case-insensitively, as DNS requires"
    (is (= :ok (:status (q "JUDAH.Kekkai.Example." "A")))))
  (testing "and this node resolves itself"
    (is (= "100.64.0.1" (get-in (first (:answers (q "asher.kekkai.example." "A")))
                                [:zone/rdata :zone/address]))))
  (testing "AAAA only when the netmap has one"
    (is (= "fd7a:115c::2" (get-in (first (:answers (q "judah.kekkai.example." "AAAA")))
                                 [:zone/rdata :zone/address])))
    (is (= :nodata (:status (q "asher.kekkai.example." "AAAA"))))))

(deftest txt-identifies-who-a-name-is
  (let [txt (get-in (first (:answers (q "judah.kekkai.example." "TXT"))) [:zone/rdata :zone/text])]
    (is (= "node=judah key=bbbbbbbb status=authorized" txt)
        "a key fingerprint, not the whole key — enough to tell two nodes apart in a log")))

(deftest reverse-lookups-name-the-overlay-range
  (is (= "judah.kekkai.example."
         (get-in (first (:answers (q "2.0.64.100.in-addr.arpa." "PTR")))
                 [:zone/rdata :zone/target])))
  (testing "but we do not claim in-addr.arpa in general"
    (is (= :refused (:status (q "1.1.1.1.in-addr.arpa." "PTR"))))))

(deftest unknown-tailnet-names-are-nxdomain-known-suffix-only
  (is (= :nxdomain (:status (q "nosuchnode.kekkai.example." "A"))))
  (testing "a multi-label name under the suffix is not a node"
    (is (= :nxdomain (:status (q "a.b.kekkai.example." "A"))))))

(deftest names-outside-the-tailnet-are-refused-so-the-chain-continues
  (testing "an overlay resolver that NXDOMAINs the public internet is a
            machine-wide outage; refusing lets the chain fall through"
    (is (= :refused (:status (q "www.example.com." "A"))))
    (is (= :refused (:status (q "kekkai.example.evil.com." "A"))))))

(deftest answers-follow-a-netmap-swap-with-no-cache-to-invalidate
  (let [current (atom nm)
        r (magicdns/netmap-resolver #(deref current) "kekkai.example")]
    (is (= :ok (:status (resolver/-resolve r "judah.kekkai.example." "A" "IN"))))
    (reset! current (update nm :netmap/peers empty))
    (is (= :nxdomain (:status (resolver/-resolve r "judah.kekkai.example." "A" "IN")))
        "a revoked peer stops resolving immediately")))

(deftest search-domain
  (is (= "kekkai.example" (magicdns/search-domain "kekkai.example"))))
