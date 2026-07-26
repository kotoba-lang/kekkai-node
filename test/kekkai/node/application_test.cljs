(ns kekkai.node.application-test
  (:require [cljs.test :refer [deftest is testing]]
            [kekkai.node.application :as application]))

(deftest messages-reassemble-out-of-order-and-remain-peer-scoped
  (let [message {:messageType "request"
                 :requestId "r-1"
                 :body (apply str (repeat 3000 "x"))}
        frames (application/encode message)
        first-result (application/accept {} "peer-a" (last frames))
        wrong-peer (application/accept (:state first-result)
                                       "peer-b" (first frames))
        completed
        (reduce
         (fn [result frame]
           (application/accept (:state result) "peer-a" frame))
         {:state (:state wrong-peer)}
         (butlast frames))]
    (is (> (count frames) 1))
    (is (= message (:message completed)))
    (is (nil? (:message wrong-peer)))
    (is (some #(= "peer-b" (first %)) (keys (:state completed))))))

(deftest hostile-frames-fail-closed
  (let [bad (vec (js/Uint8Array. (.from js/Buffer "{}" "utf8")))
        result (application/accept {} "peer" bad)]
    (is (= {} (:state result)))
    (is (string? (:error result)))))

(deftest oversized-messages-are-rejected-before-sending
  (is (thrown? js/Error
               (application/encode
                {:body (apply str (repeat (inc application/max-message-bytes)
                                          "x"))}))))
