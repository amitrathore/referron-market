(ns referron-market.shoptype-test
  (:require [clojure.test :refer :all]
            [referron-market.handlers :as handlers]
            [referron-market.spec :as spec]
            [game.gm.runtime :as runtime]
            [game.gm.shoptype :as gateway]))

(deftest shoptype-is-registered-with-private-state-protection
  (is (some #{"shoptype"} (:protocols spec/game-spec)))
  (is (some #{"shoptype"} (:protected-protocols spec/game-spec)))
  (is (fn? (get-in handlers/merged-manifest [:shoptype :query-context :handler]))))

(deftest discovery-advertises-only-the-initial-one-time-seller-direct-capability
  (let [game-id (random-uuid)]
    (with-redefs [gateway/enabled? (constantly true)
                  runtime/game-id (atom game-id)
                  runtime/game-name-handle (atom "referron-market")]
      (let [response (handlers/gm-routes {:uri "/shoptype/v0.1/manifest" :request-method :get})
            manifest (:body response)]
        (is (= 200 (:status response)))
        (is (= "shoptype" (:protocol manifest)))
        (is (= (str game-id) (:game_id manifest)))
        (is (= "referron-market" (:market_id manifest)))
        (is (= ["one-time"] (:billing_modes manifest)))
        (is (= ["seller-direct"] (:payment_routes manifest)))
        (is (not (contains? manifest :handlers)))))))

(deftest anonymous-operations-are-denied-without-changing-the-market
  (with-redefs [gateway/enabled? (constantly true)]
    (is (= 401 (:status (handlers/gm-routes {:uri "/shoptype/v0.1/operations" :request-method :post
                                            :body {:operation "query-context"}}))))))
