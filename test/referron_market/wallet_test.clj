(ns referron-market.wallet-test
  (:require [clojure.test :refer :all]
            [game.gm.config :as config]
            [game.gm.identity :as identity]
            [referron-market.wallet :as wallet]
            [referron-market.payments :as payments]))

(deftest wallet-routes-require-the-selected-authenticated-seat
  (with-redefs [identity/require-selected-seat
                (fn [& _] {:response {:status 401 :body {:error {:code "authentication-required"}}}})]
    (doseq [[method path] [[:get "/wallet"] [:get "/api/connect/status"]
                          [:get "/api/wallet/withdrawals"] [:post "/api/connect/onboard"]
                          [:post "/api/wallet/withdraw"]]]
      (is (= 401 (:status (wallet/routes {:request-method method :uri path :headers {}})))))))

(deftest disabled-withdrawals-never-call-the-payment-provider
  (with-redefs [identity/require-selected-seat
                (fn [& _] {:context {:game-name "referron-market-local" :identity {:user-id "owner"}}
                           :seat {:player-name "member" :player-roles ["coseller"]}})
                config/connect-enabled? (constantly false)
                payments/create-transfer (fn [& _] (throw (ex-info "Must not transfer" {})))
                wallet/fetch-connect-account (fn [& _] nil)]
    (is (= 404 (:status (wallet/routes {:request-method :post :uri "/api/wallet/withdraw"
                                       :headers {} :body {:amount 100}}))))))

(deftest pending-provider-result-preserves-the-reservation
  (let [submitted (atom [])]
    (with-redefs [wallet/submit-service-event (fn [& args] (swap! submitted conj args))
                  payments/create-transfer (fn [_] {:ok? false :status 503})]
      (let [response (#'wallet/complete-withdrawal "referron-market" "member" ["coseller"]
                      {:id "stable-id" :amount 100 :destination "acct_test"})]
        (is (= 503 (:status response)))
        (is (= "stable-id" (get-in response [:body :withdrawalId])))
        (is (= "pending" (get-in response [:body :status])))
        (is (empty? @submitted) "Never reverse a transfer whose outcome is unknown")))))

(deftest existing-paid-withdrawal-is-an-idempotent-noop
  (with-redefs [config/connect-enabled? (constantly true)
                wallet/query-own-withdrawal (fn [& _] {:ok? true :record {:id "stable-id" :amount 100 :status "paid"}})
                payments/create-transfer (fn [& _] (throw (ex-info "Duplicate transfer" {})))]
    (let [r (#'wallet/request-withdrawal {:game-name "referron-market" :identity {:user-id "owner"}}
             {:player-name "member" :player-roles ["coseller"]}
             {:body {:amount 100 :withdrawalId "stable-id"}})]
      (is (= 200 (:status r)))
      (is (= "paid" (get-in r [:body :status]))))))
