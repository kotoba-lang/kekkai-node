(ns kekkai.node.signed-netmap-test
  (:require [cljs.reader :as reader]
            [cljs.test :refer [deftest is testing]]
            [kekkai.node.signed-netmap :as signed]))

(def payload
  (pr-str
   {:netmap/version 1
    :netmap/tailnet "test"
    :netmap/self {:node/id "a" :node/key "aa"}
    :netmap/peers []}))

(defn key-material []
  (let [crypto (js/require "node:crypto")
        pair (.generateKeyPairSync crypto "ed25519")
        private-key (.-privateKey pair)
        public-key (.-publicKey pair)
        spki (.toString
              (.export public-key #js {:format "der" :type "spki"})
              "base64")]
    {:crypto crypto :private private-key :spki spki}))

(defn envelope [material body]
  (let [bytes (.from js/Buffer body "utf8")
        signature (.sign (:crypto material) nil bytes (:private material))
        digest (-> (.createHash (:crypto material) "sha256")
                   (.update bytes) (.digest "hex"))]
    (pr-str {:netmap/payload-b64 (.toString bytes "base64")
             :netmap/signature-b64 (.toString signature "base64")
             :netmap/signer-spki-b64 (:spki material)
             :netmap/sha256 digest})))

(deftest byte-exact-signature-is-required
  (let [material (key-material)
        text (envelope material payload)]
    (is (= 1 (:netmap/version
              (signed/verify-envelope text (:spki material)))))
    (testing "a separately configured authority is the trust anchor"
      (is (thrown-with-msg?
           js/Error #"configured authority"
           (signed/verify-envelope text (:spki (key-material))))))
    (testing "payload tampering is rejected before EDN is trusted"
      (let [parsed (reader/read-string text)
            tampered (pr-str
                      (assoc parsed :netmap/payload-b64
                             (.toString
                              (.from js/Buffer (str payload " ") "utf8")
                              "base64")))]
        (is (thrown-with-msg? js/Error #"digest mismatch"
                              (signed/verify-envelope
                               tampered (:spki material))))))))
