(ns kekkai.node.relay-test
  "The relay's routing, registration and roaming rules, driven through an
  in-memory network — real Noise handshakes, no sockets."
  (:require [clojure.test :refer [deftest is testing]]
            [kekkai.node.relay :as relay]
            [kotoba.bytes :as b]
            [noise.core :as noise]
            #?(:clj [noise.provider.jvm :as provider]
               :cljs [noise.provider.node :as provider])))

(def suite (noise/suite (provider/ports)))
(def prologue (b/utf8-encode "kekkai-node/1 tailnet:test netmap:1"))

(defn- kp [] (noise/keypair suite))

(defn- connect
  "Run a client's handshake against the server. -> [server client]"
  [server client addr now]
  (let [[client hello] (relay/client-hello client now)
        {srv :state :keys [send]} (relay/server-on-datagram
                                   server {:from addr :bytes (:bytes hello) :now now})
        {cl :state} (relay/client-on-datagram client {:bytes (:bytes (first send)) :now now})]
    [srv cl]))

(defn- fixture []
  (let [relay-static (kp) a (kp) bb (kp)
        server (relay/server {:suite suite :static relay-static :region "test"
                              :prologue prologue})
        mk (fn [static] (relay/client {:suite suite :static static
                                       :relay-key (:pub relay-static)
                                       :relay-addr "relay:1" :region "test"
                                       :prologue prologue}))
        [server ca] (connect server (mk a) "10.0.0.1:1111" 1000)
        [server cb] (connect server (mk bb) "10.0.0.2:2222" 1000)]
    {:server server :ca ca :cb cb :a a :b bb :relay-static relay-static :mk mk}))

(deftest handshake-registers-the-authenticated-key
  (let [{:keys [server ca a b]} (fixture)]
    (is (relay/connected? ca))
    (is (= #{(b/hex (:pub a)) (b/hex (:pub b))} (relay/registered-keys server))
        "the relay routes to keys the handshake proved, never to self-asserted ones")))

(deftest a-client-that-cannot-authenticate-is-not-registered
  (testing "garbage, and a handshake aimed at the wrong relay key, both fail"
    (let [{:keys [server mk]} (fixture)
          wrong-relay (kp)
          impostor (relay/client {:suite suite :static (kp) :relay-key (:pub wrong-relay)
                                  :relay-addr "relay:1" :prologue prologue})
          [_impostor hello] (relay/client-hello impostor 2000)
          {srv :state :keys [events]} (relay/server-on-datagram
                                       server {:from "10.0.0.9:9999"
                                               :bytes (:bytes hello) :now 2000})]
      (is (= 2 (count (relay/registered-keys srv))) "no new registration")
      (is (= :handshake-rejected (:event (first events))))
      (let [{:keys [events]} (relay/server-on-datagram
                              server {:from "10.0.0.9:9999" :bytes [0 1 2] :now 2000})]
        (is (= :dropped (:event (first events))))))
    (testing "and a session frame from an unknown address is dropped, not guessed"
      (let [{:keys [server]} (fixture)
            {:keys [events]} (relay/server-on-datagram
                              server {:from "10.9.9.9:1" :bytes (relay/encode-frame :session [1 2 3])
                                      :now 2000})]
        (is (= :unregistered-address (:reason (first events))))))))

(deftest forwards-a-sealed-payload-without-being-able-to-read-it
  (let [{:keys [server ca cb a b]} (fixture)
        payload (b/utf8-encode "already sealed for b")
        [_ca dg] (relay/client-send ca (:pub b) payload 1100)
        {_server :state :keys [send events]} (relay/server-on-datagram
                                            server {:from "10.0.0.1:1111"
                                                    :bytes (:bytes dg) :now 1100})
        {_cb :state cb-events :events} (relay/client-on-datagram
                                       cb {:bytes (:bytes (first send)) :now 1100})
        ev (first cb-events)]
    (is (= :forwarded (:event (first events))))
    (is (= :packet (:event ev)))
    (is (= (b/hex (:pub a)) (:src ev)) "the receiver learns who sent it")
    (is (= (vec payload) (vec (:payload ev))))
    (testing "the relay never sees the payload in the clear: what it forwards is
              opaque bytes, and the only plaintext it handles is the destination key"
      (is (not= (vec payload) (vec (:bytes (first send))))))))

(deftest signalling-reaches-a-peer-with-no-direct-path
  (let [{:keys [server ca cb b]} (fixture)
        [_ca dg] (relay/client-signal ca (:pub b) (b/utf8-encode "[{:kind :local}]") 1200)
        {_server :state :keys [send]} (relay/server-on-datagram
                                      server {:from "10.0.0.1:1111" :bytes (:bytes dg) :now 1200})
        {:keys [events]} (relay/client-on-datagram cb {:bytes (:bytes (first send)) :now 1200})]
    (is (= :signal (:event (first events))))
    (is (= "[{:kind :local}]" (apply str (map char (:payload (first events))))))))

(deftest an-absent-destination-is-reported-not-dropped
  (let [{:keys [server ca]} (fixture)
        ghost (:pub (kp))
        [ca dg] (relay/client-send ca ghost [1 2 3] 1300)
        {_server :state :keys [send] :as _out} (relay/server-on-datagram
                                            server {:from "10.0.0.1:1111"
                                                    :bytes (:bytes dg) :now 1300})
        {:keys [events]} (relay/client-on-datagram ca {:bytes (:bytes (first send)) :now 1300})]
    (is (= :not-here (:event (first events)))
        "the sender learns the peer is elsewhere instead of silently blackholing")))

(deftest roaming-requires-authentication
  (testing "a client that re-handshakes from a new address moves; a spoofed
            address cannot move it, because only the handshake (or a frame that
            decrypts) updates the routing table"
    (let [{:keys [server cb a mk]} (fixture)
          ;; a roams: new address, fresh handshake
          [server _ca2] (connect server (mk a) "192.0.2.5:5555" 1400)
          payload (b/utf8-encode "to the roamed a")
          [_cb dg] (relay/client-send cb (:pub a) payload 1500)
          {:keys [send events]} (relay/server-on-datagram
                                 server {:from "10.0.0.2:2222" :bytes (:bytes dg) :now 1500})]
      (is (= "192.0.2.5:5555" (:to (first send))) "traffic follows the new address")
      (is (= :forwarded (:event (first events))))
      (testing "and the stale address no longer routes"
        (let [{:keys [events]} (relay/server-on-datagram
                                server {:from "10.0.0.1:1111"
                                        :bytes (relay/encode-frame :session [9 9 9])
                                        :now 1500})]
          (is (= :unregistered-address (:reason (first events)))))))))

(deftest idle-clients-expire
  (let [{:keys [server]} (fixture)
        {:keys [state events]} (relay/server-tick server (+ 1000 100000))]
    (is (= 2 (count events)))
    (is (empty? (relay/registered-keys state)))))

(deftest framing-rejects-hostile-input
  (is (= :short-frame (:error (relay/decode-frame [1]))))
  (is (= :bad-magic (:error (relay/decode-frame [0 1 2 3]))))
  (is (= :bad-version (:error (relay/decode-frame [relay/magic 99 0x20]))))
  (is (= :unknown-frame-type (:error (relay/decode-frame [relay/magic 1 0x77]))))
  (is (= :short-inner (:error (relay/decode-inner (vec (repeat 10 0)))))))

(deftest home-relay-selection-is-deterministic
  (let [relays [{:relay/name "jp-tyo-1" :relay/region "jp"}
                {:relay/name "us-sjc-1" :relay/region "us"}]]
    (is (= "us-sjc-1" (:relay/name (relay/home relays {"jp-tyo-1" 90 "us-sjc-1" 30}))))
    (testing "an unmeasured relay is not guessed at — a same-region relay only
              wins when nothing has been measured"
      (is (= "jp-tyo-1" (:relay/name (relay/home relays {"jp-tyo-1" 90}))))
      (is (= "jp-tyo-1" (:relay/name (relay/home relays {} {:prefer-region "jp"}))))
      (is (= "us-sjc-1" (:relay/name (relay/home relays {} {:prefer-region "us"})))))))
