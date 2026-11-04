(ns referron-market.seller-connect
  "OAuth authorization for seller-owned Standard accounts; legacy Yvatar remains pinned."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [game.gm.config :as gm-config]
            [game.gm.identity :as identity]
            [game.gm.runtime :as runtime]
            [ring.util.codec :as codec]
            [referron-market.payments :as payments])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(defn- env [name]
  (some-> (System/getenv name) str str/trim not-empty))

(defn- game-name [] (some-> @runtime/game-name-handle str))

(defn- callback-url []
  (when-let [base (gm-config/resolve-app-base-url)]
    (str (str/replace base #"/+$" "") "/api/connect/stripe/callback")))

(defn- return-url [result & [reason]]
  (str (str/replace (or (gm-config/resolve-app-base-url) "") #"/+$" "")
       "/?connect=" result (when reason (str "&reason=" reason))))

(defn- connect-error [reason & [status]]
  ;; Only fixed reason codes and HTTP statuses reach the URL or logs. OAuth
  ;; codes, signed state, and Stripe's response body must never be reflected.
  (println "seller-connect callback failed" reason (or status ""))
  {:status 302 :headers {"Location" (return-url "error" reason)}})

(defn- connection-error-reason [connection]
  (if (= "seller-account-not-ready" (:error-code connection))
    "seller-account-not-ready"
    (let [stripe-code (or (get-in connection [:error :error])
                          (get-in connection [:error "error"]))]
      (case stripe-code
        "invalid_grant" "oauth-invalid-grant"
        "invalid_client" "oauth-invalid-client"
        "invalid_request" "oauth-invalid-request"
        "oauth-exchange-failed"))))

(defn- signature [attempt-id]
  (when-let [secret (env "STRIPE_CONNECT_STATE_SECRET")]
    (let [mac (Mac/getInstance "HmacSHA256")]
      (.init mac (SecretKeySpec. (.getBytes secret StandardCharsets/UTF_8) "HmacSHA256"))
      (apply str (map #(format "%02x" (bit-and 0xff %))
                      (.doFinal mac (.getBytes (str attempt-id) StandardCharsets/UTF_8)))))))

(defn- verified-attempt-id [state]
  (let [[attempt-id supplied extra] (str/split (str state) #"\." 3)
        expected (signature attempt-id)]
    (when (and (nil? extra) expected supplied
               (MessageDigest/isEqual (.getBytes ^String expected StandardCharsets/UTF_8)
                                      (.getBytes ^String supplied StandardCharsets/UTF_8)))
      attempt-id)))

(defn- seller-seat? [seat]
  (let [roles (or (:player-roles seat)
                  (some-> seat :player-role vector)
                  [])]
    (and (seq (:player-name seat))
         (contains? (set (map (comp str/lower-case name) roles)) "seller"))))

(defn- request! [method path body]
  ((case method :post http/post)
   (str (identity/mom-url nil) path)
   (identity/with-auth {:content-type :json
                        :body (json/generate-string body)
                        :as :json :coerce :unexceptional :throw-exceptions false})))

(defn status [context seat]
  (if-not (seller-seat? seat)
    {:status 403 :body {:error {:code "seller-seat-required"}}}
    (let [response (http/get
                    (str (identity/mom-url nil) "/api/v1/connect/" (game-name) "/account")
                    (identity/with-auth {:query-params
                                         {:owner-id (str (get-in context [:identity :user-id]))}
                                         :as :json :coerce :unexceptional
                                         :throw-exceptions false}))
          account (:body response)]
      (if (= 200 (:status response))
        {:status 200
         :body {:status (or (:status account) (get account "status") "none")
                :accountType (or (:accountType account) (get account "accountType"))
                :paymentsEnabled (true? (or (:paymentsEnabled account)
                                            (get account "paymentsEnabled")))
                :payoutsEnabled (true? (or (:payoutsEnabled account)
                                           (get account "payoutsEnabled")))}}
        {:status 503 :body {:error {:code "seller-connect-status-unavailable"}}}))))

(defn- environment []
  (if (str/starts-with? (str (or (env "STRIPE_CONNECT_SECRET_KEY") (gm-config/resolve-stripe-secret-key))) "sk_test_") "test" "live"))

(defn start [context seat]
  (let [client-id (env "STRIPE_CONNECT_CLIENT_ID")
        callback (callback-url)
        expected-account (env "YVATAR_STRIPE_EXPECTED_ACCOUNT_ID")
        owner-id (get-in context [:identity :user-id])
        game (game-name)
        attempt-id (str (random-uuid))
        state (when-let [sig (signature attempt-id)] (str attempt-id "." sig))]
    (cond
      (not (seller-seat? seat))
      {:status 403 :body {:error {:code "seller-seat-required"}}}
      (or (str/blank? client-id) (str/blank? callback)
          (and (= "yvatar" (:player-name seat)) (str/blank? expected-account)) (str/blank? state)
          (not (re-find #"^sk_(live|test)_" (str (or (env "STRIPE_CONNECT_SECRET_KEY") (gm-config/resolve-stripe-secret-key)))))
          (str/blank? (str owner-id)) (str/blank? game))
      {:status 503 :body {:error {:code "seller-connect-not-configured"}}}
      :else
      (let [stored (request! :post (str "/api/v1/connect/" game "/attempts")
                             {:attempt-id attempt-id :owner-id (str owner-id)
                              :membership-id (or (:seat-id seat) (:membership-id seat))
                              :provider "stripe" :environment (environment)
                              :return-path "/" :expires-at (+ (System/currentTimeMillis) 600000)})]
        (if (= 200 (:status stored))
          (let [started (payments/start-seller-connection
                         {:client-id client-id :redirect-uri callback :state state})]
            (if (:ok? started)
              {:status 200 :body {:url (:url started)}}
              {:status 503 :body {:error {:code (:error-code started)}}}))
          {:status (:status stored) :body (:body stored)})))))

(defn complete [request]
  ;; This route uses the raw Ring request. Referron Market does not install wrap-params,
  ;; so :query-params and :params can both be absent on Stripe's redirect.
  (let [raw-params (try (codec/form-decode (or (:query-string request) ""))
                        (catch IllegalArgumentException _ {}))
        params (merge (:params request) (:query-params request) raw-params)
        state (or (get params "state") (:state params))
        code (or (get params "code") (:code params))
        stripe-error (or (get params "error") (:error params))
        attempt-id (verified-attempt-id state)
        game (game-name)
        expected-account (env "YVATAR_STRIPE_EXPECTED_ACCOUNT_ID")]
    (cond
      (not (str/blank? (str (or stripe-error "")))) (connect-error "stripe-authorization-denied")
      (str/blank? attempt-id) (connect-error "invalid-state")
      (str/blank? (str (or code ""))) (connect-error "missing-code")
      (str/blank? game)
      (connect-error "configuration-unavailable")
      :else
      (let [consumed (request! :post (str "/api/v1/connect/" game
                                          "/attempts/" attempt-id "/consume") {})
            attempt (or (get-in consumed [:body :attempt])
                        (get-in consumed [:body "attempt"]))]
        (cond
          (or (not= 200 (:status consumed)) (nil? attempt))
          (connect-error "attempt-unavailable" (:status consumed))
          (or (str/blank? (str (or (:playerName attempt) (get attempt "playerName"))))
              (not= (environment) (str/lower-case (str (or (:environment attempt) (get attempt "environment"))))))
          (connect-error "attempt-mismatch")
          :else
          (let [pinned? (= "yvatar" (str/lower-case (str (or (:playerName attempt) (get attempt "playerName")))))
                expected-account (when pinned? expected-account)
                connection (payments/complete-seller-connection {:code code})
                owner-id (or (:ownerId attempt) (get attempt "ownerId"))
                saved (when (and (:ok? connection)
                                 (or (not pinned?) (= expected-account (:external-id connection))))
                        (request! :post (str "/api/v1/connect/" game "/account/create")
                                  {:owner-id owner-id :provider "stripe"
                                   :environment (environment)
                                   :external-id (:external-id connection)
                                   :account-type "standard"
                                   :connection-method "oauth"
                                   :purposes ["seller-direct" "payout"]
                                   :status "enabled"
                                   :charges-enabled true
                                   :card-payments-enabled true
                                   :payouts-enabled true
                                   :provider-details (:provider-details connection)}))]
            (cond
              (not (:ok? connection))
              (connect-error (connection-error-reason connection)
                             (:status connection))
              (and pinned? (not= expected-account (:external-id connection)))
              (connect-error "account-mismatch")
              (not= 200 (:status saved))
              (connect-error "account-save-failed" (:status saved))
              :else
              {:status 302 :headers {"Location" (return-url "return")}})))))))

(defn shoptype-handlers [options]
  (into {} (for [[operation handler] [["payment-status" status] ["start-payment-connection" start]]]
             [operation (fn [request]
                          (let [{:keys [context seat response]} (identity/require-selected-seat request options)]
                            (or response (handler context seat))))])))
