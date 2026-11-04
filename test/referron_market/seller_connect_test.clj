(ns referron-market.seller-connect-test
  (:require [clojure.test :refer [deftest is testing]]
            [referron-market.seller-connect :as connect]
            [referron-market.payments :as payments]
            [game.gm.config :as gm-config]))

(defn- with-connect-stubs [stubs f]
  (with-redefs-fn
    (merge {#'referron-market.seller-connect/env
            (fn [name] (get {"STRIPE_CONNECT_STATE_SECRET" "test-secret"
                             "YVATAR_STRIPE_EXPECTED_ACCOUNT_ID" "acct_expected"}
                            name))
            #'referron-market.seller-connect/game-name (constantly "referron-market")
            #'gm-config/resolve-app-base-url (constantly "https://www.referron.com")}
           stubs)
    f))

(deftest start-requires-configured-seller-seat
  (is (= 503 (:status (connect/start {:identity {:user-id "owner"}}
                                      {:player-name "other" :player-roles ["seller"]}))))
  (is (= 403 (:status (connect/start {:identity {:user-id "owner"}}
                                      {:player-name "yvatar" :player-roles ["buyer"]})))))

(deftest callback-rejects-tampered-and-wrong-account
  (let [calls (atom [])
        attempt-id "b34065ec-7411-4510-a194-470566e3a27f"
        valid-state (str attempt-id ".")]
    (with-connect-stubs
      {#'referron-market.seller-connect/request!
       (fn [_ path _]
         (swap! calls conj path)
         {:status 200 :body {:attempt {:ownerId "owner" :playerName "yvatar"
                                       :environment "live"}}})
       #'payments/complete-seller-connection
       (fn [_] {:ok? true :external-id "acct_other"})}
      (fn []
        (testing "a forged state never consumes an attempt"
          (is (= "https://www.referron.com/?connect=error&reason=invalid-state"
                 (get-in (connect/complete {:query-params
                                            {"state" (str valid-state "wrong")
                                             "code" "ac_test"}})
                         [:headers "Location"])))
          (is (empty? @calls)))
        (let [signed (str attempt-id "."
                          (#'referron-market.seller-connect/signature attempt-id))
              result (connect/complete {:query-string (str "state=" signed "&code=ac_test")})]
          (testing "a different Stripe account is never registered"
            (is (= "https://www.referron.com/?connect=error&reason=account-mismatch"
                   (get-in result [:headers "Location"])))
          (is (= 1 (count @calls)))))))))

(deftest callback-reports-safe-failure-reasons
  (let [attempt-id "b34065ec-7411-4510-a194-470566e3a27f"
        signed (str attempt-id "."
                    (with-connect-stubs {}
                      (fn [] (#'referron-market.seller-connect/signature attempt-id))))
        request (fn [params] (get-in (connect/complete {:query-params params})
                                    [:headers "Location"]))]
    (with-connect-stubs
      {#'referron-market.seller-connect/request!
       (fn [_ _ _] {:status 200 :body {:attempt {:ownerId "owner"
                                                :playerName "yvatar"
                                                :environment "live"}}})
       #'payments/complete-seller-connection
       (fn [_] {:ok? false :status 400 :error-code "oauth-exchange-failed"
                :error {:error "invalid_grant" :error_description "secret-stripe-error"}})}
      (fn []
        (is (= "https://www.referron.com/?connect=error&reason=stripe-authorization-denied"
               (request {"error" "secret-stripe-error" "state" signed})))
        (is (= "https://www.referron.com/?connect=error&reason=missing-code"
               (request {"state" signed})))
        (is (= "https://www.referron.com/?connect=error&reason=oauth-invalid-grant"
               (request {"state" signed "code" "secret-code"})))))))

(deftest callback-reads-raw-stripe-query-string
  (let [attempt-id "00000000-0000-4000-8000-000000000000"]
    (with-connect-stubs
      {#'referron-market.seller-connect/request!
       (fn [_ _ _] (throw (ex-info "must not consume without a code" {})))}
      (fn []
        (let [state (str attempt-id "."
                         (#'referron-market.seller-connect/signature attempt-id))]
          (is (= "https://www.referron.com/?connect=error&reason=missing-code"
                 (get-in (connect/complete {:query-string (str "state=" state)})
                         [:headers "Location"]))))))))

(deftest callback-registers-only-verified-yvatar-account
  (let [saved (atom nil)
        attempt-id "a9d6e389-5ac6-4c70-bfa3-536043855ffd"]
    (with-connect-stubs
      {#'referron-market.seller-connect/request!
       (fn [_ path body]
         (if (.endsWith path "/consume")
           {:status 200 :body {:attempt {:ownerId "owner" :playerName "yvatar"
                                         :environment "live"}}}
           (do (reset! saved body) {:status 200 :body {:success true}})))
       #'payments/complete-seller-connection
       (fn [_] {:ok? true :external-id "acct_expected"
                :provider-details {:country "US"}})}
      (fn []
        (let [signed (str attempt-id "."
                          (#'referron-market.seller-connect/signature attempt-id))
              result (connect/complete {:query-string (str "state=" signed "&code=ac_test")})]
          (is (= "https://www.referron.com/?connect=return"
                 (get-in result [:headers "Location"])))
          (is (= "acct_expected" (:external-id @saved)))
          (is (= "owner" (:owner-id @saved)))
          (is (= "standard" (:account-type @saved)))
          (is (= ["seller-direct" "payout"] (:purposes @saved))))))))
