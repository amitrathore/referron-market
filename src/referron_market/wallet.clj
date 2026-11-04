(ns referron-market.wallet
  "Wallet HTTP orchestration adapted from the-js-game; protocols are unchanged."
  (:require [clojure.string :as str] [cheshire.core :as json]
            [clj-http.client :as http] [compojure.core :refer [defroutes GET POST]]
            [game.gm.config :as gm-config] [game.gm.identity :as identity]
            [game.gm.runtime :as runtime] [referron-market.payments :as payments]))
(def mom-url identity/mom-url)
(def with-auth identity/with-auth)
(defn- current-game-name [] (some-> @runtime/game-name-handle str))
(defn- submit-service-event [game-name & args]
  (when-not (= game-name (current-game-name)) (throw (ex-info "Wrong game" {})))
  (apply identity/submit-service-event game-name args))
(defn- require-selected-seat [request]
  (identity/require-selected-seat request
    {:game-id (some-> @runtime/game-id str) :game-name (current-game-name) :select-sole-seat? true}))
(defn- seat-summary [seat] (select-keys seat [:player-name :player-roles]))
(defn- seller-role? [seat] (some #{"seller"} (:player-roles seat)))
(defn- ui-return-path [_] "/earnings")
(def ^:private wallet-currency "usd-cents")

(defn- fetch-wallet-balance
  "A player's shadow-ledger balance from MoM, as an integer in the wallet
   currency's minor units. A player who has never had a ledger op has no account
   yet — MoM answers account-not-found — which reads as 0 rather than an error.
   Uses the GM service token (with-auth); :coerce :unexceptional avoids trying to
   JSON-parse MoM's non-2xx error body."
  [game-name player-name]
  (try
    (let [encoded (-> (java.net.URLEncoder/encode (str player-name) "UTF-8")
                      (str/replace "+" "%20"))
          url (str (mom-url nil) "/api/v1/ledger/" game-name "/balance/" encoded)
          resp (http/get url (with-auth {:query-params {:currency wallet-currency}
                                         :as :json
                                         :coerce :unexceptional
                                         :throw-exceptions false}))
          body (:body resp)
          data (or (:data body) (get body "data"))
          balance (or (:balance data) (get data "balance")
                      (:balance body) (get body "balance"))]
      (if (and (= 200 (:status resp)) (number? balance))
        (long balance)
        0))
    (catch Exception _ 0)))

(defn- seat-roles-vec
  [seat]
  (vec (or (:player-roles seat)
           (some-> seat :player-role vector)
           ["buyer"])))

(defn- fetch-connect-account
  "GET the (owner, game) payout account from MoM (service auth). Returns the
   parsed body map (with :status/:externalId/:payoutsEnabled) or nil on error."
  [game-name owner-id]
  (try
    (let [resp (http/get (str (mom-url nil) "/api/v1/connect/" game-name "/account")
                         (with-auth {:query-params {:owner-id (str owner-id)}
                                     :as :json :coerce :unexceptional :throw-exceptions false}))]
      (when (= 200 (:status resp)) (:body resp)))
    (catch Exception _ nil)))

(defn- upsert-connect-account!
  "PUT the (owner, game) payout account to MoM (service auth)."
  [game-name owner-id fields]
  (http/put (str (mom-url nil) "/api/v1/connect/" game-name "/account")
            (with-auth {:content-type :json
                        :body (json/generate-string (assoc fields :owner-id (str owner-id)))
                        :as :json :coerce :unexceptional :throw-exceptions false})))

(defn- effect-succeeded?
  "True when the first ledger effect in a MoM /action response committed."
  [resp]
  (let [body (:body resp)
        results (or (:effect-results body) (get body "effect-results"))
        status (some-> (first results) (as-> r (or (:status r) (get r "status"))) str)]
    (= "succeeded" status)))

(defn- gm-data
  [resp]
  (let [body (:body resp)
        gm-result (or (:gm-result body) (get body "gm-result"))]
    (or (:data gm-result) (get gm-result "data"))))

(defn- settlement-field
  [s & ks]
  (some (fn [k] (or (get s k) (get s (name k)))) ks))

(defn- player-credit
  "The player's own credited amount in a settlement: their coseller split plus
   their seller-net if they are the seller. Tolerates JSON string-key drift."
  [s player-name]
  (let [splits (or (settlement-field s :splits) {})
        split (or (get splits player-name) (get splits (keyword player-name)))
        split-amt (or (and split (or (:amount split) (get split "amount"))) 0)
        seller (settlement-field s :seller)
        seller-net (if (= seller player-name)
                     (let [sn (settlement-field s :seller-net)]
                       (or (and sn (or (:amount sn) (get sn "amount"))) 0))
                     0)]
    (+ (long split-amt) (long seller-net))))

(defn- player-settlements
  "Union of settlements where the player earns (as coseller or seller)."
  [game-name player-name]
  (let [pull (fn [filters]
               (let [resp (submit-service-event game-name "coseller/query-settlements"
                                                player-name ["buyer"] {:filters filters})
                     data (gm-data resp)]
                 (or (:settlements data) (get data "settlements") [])))]
    (->> (concat (pull {:coseller player-name}) (pull {:seller player-name}))
         (map (fn [s] [(settlement-field s :order-id) s]))
         (into {})
         vals)))

(defn- order-field
  [order k]
  (or (get order k) (get order (name k))))

(defn- money-amount
  "Minor-unit amount out of a {:amount n :currency c} money map, 0 when absent."
  [m]
  (let [a (or (:amount m) (get m "amount"))]
    (if (number? a) (long a) 0)))

(defn- buyer-funding-in-flight
  "Sum of the player's own purchases whose money is still parked in their wallet.

   Applies only to checkouts funded BEFORE buyer-funding-escrow shipped.
   `market/mark-checkout-paid` used to mint the checkout gross into the BUYER's
   own wallet, with `coseller/settlement-ledger-batch` debiting it at settlement.
   Between those points the buyer held money they had already spent — not theirs,
   and not withdrawable: draining it failed the settlement batch atomically on
   MoM's no-overdraft rule and left every payee on the order unpaid. Funding now
   mints to `@buyer-escrow` instead, so this guard is a legacy safety net.

   An order's gross is still parked when it has NO settlement record. Fully
   refunded orders do get a terminal `:void-refunded` settlement, so they fall out
   of this sum correctly. Committed refunds already debited the wallet, so the
   refunded portion is netted off rather than counted twice.

   Counts ONLY checkouts funded before buyer-funding-escrow shipped — those carry
   no `:funding-account` stamp and really do hold their gross in the buyer's
   wallet. Escrow-funded checkouts keep their gross in `@buyer-escrow`, which was
   never in the wallet; subtracting those too would double-count and zero out a
   seller's genuinely earned balance (caught in the 2026-08-02 live E2E, where a
   seller holding 2489 of earnings plus 3000 of escrow computed to 0 available)."
  [game-name player-name]
  (let [gm-query (fn [event-type filters]
                   (let [resp (submit-service-event game-name event-type
                                                    player-name ["buyer"] {:filters filters})]
                     (gm-data resp)))
        orders (let [d (gm-query "market/query-orders" {:buyer player-name})]
                 (or (:orders d) (get d "orders") []))
        ;; :funding-account is stamped on the CHECKOUT, not the order, so resolve
        ;; it per order via checkout-id.
        funding-by-checkout (let [d (gm-query "market/query-checkouts" {:buyer player-name})
                                  cs (or (:checkouts d) (get d "checkouts") [])]
                              (into {} (map (fn [c]
                                              [(str (or (:id c) (get c "id")))
                                               (str (or (:funding-account c)
                                                        (get c "funding-account")))])
                                            cs)))
        settled (let [d (gm-query "coseller/query-settlements" {:buyer player-name})
                      ss (or (:settlements d) (get d "settlements") [])]
                  (set (map #(settlement-field % :order-id) ss)))
        funded? #{"paid" "payment-received"}]
    (->> orders
         (filter (fn [o]
                   (and (= player-name (str (order-field o :buyer)))
                        (funded? (str (order-field o :status)))
                        ;; escrow-funded gross is not in the wallet — see docstring
                        (str/blank? (get funding-by-checkout
                                         (str (order-field o :checkout-id))))
                        (not (contains? settled (str (order-field o :id)))))))
         (map (fn [o]
                (let [gross (money-amount (order-field o :total))
                      refunded (money-amount (:refunded (order-field o :refund-summary)))]
                  (max 0 (- gross refunded)))))
         (reduce + 0))))

(defn- compute-availability
  "Splits a player's ledger balance into what is withdrawable now vs. still
   maturing, using each settlement's Stripe available_on. Available-now = balance
   minus the player's not-yet-matured credits and minus their own un-settled
   purchases; pending lists the maturing credits with dates.
   Degrades to balance-is-available when no maturity data is present.

   NOTE: subtracting `buyer-funding-in-flight` here guards exactly ONE endpoint
   (POST /api/wallet/withdraw). It is a mitigation, not the fix — the money is
   still sitting in a player wallet where it does not belong, is still reported as
   a participant claim by portal.economy, and any other reader of a wallet balance
   remains wrong. The structural fix is the `@buyer-escrow` account
   (openspec/changes/buyer-funding-escrow, slices 2-5)."
  [game-name player-name balance now-secs]
  (let [pending (->> (player-settlements game-name player-name)
                     (keep (fn [s]
                             (let [credit (player-credit s player-name)
                                   ao (settlement-field s :stripe-available-on)]
                               (when (and (pos? credit) (number? ao) (> (long ao) now-secs))
                                 {:amount credit :availableOn (long ao)}))))
                     vec)
        pending-total (reduce + 0 (map :amount pending))
        in-flight (buyer-funding-in-flight game-name player-name)]
    {:availableNow (max 0 (- balance pending-total in-flight))
     :pending (cond-> pending
                (pos? in-flight)
                (conj {:amount in-flight :reason "purchase-in-escrow"}))}))

(defn- connect-onboarding-urls [_]
  (let [base (str/replace (or (gm-config/resolve-app-base-url) "") #"/+$" "")]
    {:refresh-url (str base "/earnings?connect=refresh")
     :return-url (str base "/earnings?connect=return")}))

(defn- create-onboarding-link
  "Ensures the player has an Express account (creating + persisting one if needed)
   and returns a fresh hosted onboarding Account Link."
  [game-name owner-id email return-path]
  (let [existing (fetch-connect-account game-name owner-id)
        existing-acct (:externalId existing)
        acct-id (if (str/blank? (str existing-acct))
                  (let [created (payments/create-connected-account
                                 {:owner-id owner-id
                                  :game-name game-name :email email})]
                    (when (:ok? created)
                      (upsert-connect-account! game-name owner-id
                                               {:external-id (:id created) :status "onboarding"})
                      (:id created)))
                  existing-acct)]
    (if (str/blank? (str acct-id))
      {:ok? false :error "account-create-failed"}
      (payments/create-account-link
       (merge {:account-id acct-id} (connect-onboarding-urls return-path))))))

(defn- withdrawal-account-ready?
  "Canonical purpose readiness takes precedence over the legacy aggregate status."
  [account roles]
  (let [earning-roles (set (filter #{"seller" "coseller"} roles))
        uses (set (:authorizedUses account))]
    (and (or (not (contains? account :authorizedUses))
             (and (seq earning-roles) (every? uses earning-roles)))
         (if (contains? (:readiness account) :withdrawals)
           (true? (get-in account [:readiness :withdrawals]))
           (= "enabled" (:status account))))))

(defn- query-own-withdrawal
  [game-name player-name roles id]
  (let [response (submit-service-event game-name "withdrawal/query" player-name roles {})
        records (:withdrawals (gm-data response))]
    (if (sequential? records)
      {:ok? true :record (some #(when (= id (:id %)) %) records)}
      {:ok? false})))

(defn- complete-withdrawal
  [game-name player-name roles record]
  (let [wid (:id record)
        amount (:amount record)
        event-actor (when-not (:from-account record) player-name)
        source (select-keys record [:from-account])
        ;; An unknown provider outcome retains the reservation and idempotency key.
        ;; Releasing it could fund a second withdrawal after Stripe already paid.
        transfer (try
                   (payments/create-transfer
                    {:amount amount :currency "usd" :destination (:destination record)
                     :withdrawal-id wid :game-name game-name :player-name player-name})
                   (catch Exception _ {:ok? false :status 503}))]
    (cond
      (:ok? transfer)
      (do
        (submit-service-event game-name "withdrawal/settle" event-actor roles
                              (merge source {:withdrawal-id wid :stripe-transfer-id (:id transfer)}))
        {:status 200 :body {:withdrawalId wid :status "paid" :amount amount
                            :stripeTransferId (:id transfer) :destination (:destination record)
                            :account (:from-account record)}})

      (or (>= (or (:status transfer) 400) 500)
          (contains? #{409 429} (:status transfer)))
      {:status 503 :body {:withdrawalId wid :status "pending"
                          :error {:code "transfer-outcome-pending"
                                  :message "Retry this withdrawal with the same withdrawalId"}}}

      :else
      (do
        (submit-service-event game-name "withdrawal/reverse" event-actor roles
                              (merge source {:withdrawal-id wid
                                             :reason (or (:error-code transfer) "transfer-failed")}))
        {:status 402 :body {:error {:code "platform-balance-insufficient"
                                    :message "Withdrawal temporarily unavailable; please try again later"
                                    :reason (:error-code transfer)}
                            :withdrawalId wid :status "rejected"}}))))

(defn- request-withdrawal
  "Resolve the owner's current Game assignment for a new reserve. A retry uses
   the original reservation's destination, assignment, source and provider key."
  [context seat request]
  (let [game-name (:game-name context)
        player-name (:player-name seat)
        owner-id (:user-id (:identity context))
        roles (seat-roles-vec seat)
        amount (try (long (or (get-in request [:body :amount]) 0)) (catch Exception _ 0))
        requested-id (get-in request [:body :withdrawalId])
        lookup (when (and (string? requested-id) (not (str/blank? requested-id)))
                 (query-own-withdrawal game-name player-name roles requested-id))
        existing (:record lookup)
        connect (when-not existing (fetch-connect-account game-name owner-id))
        wid (or requested-id (str (random-uuid)))
        record (or existing
                   (cond-> {:id wid :player-name player-name :amount amount
                            :currency wallet-currency :destination (:externalId connect)}
                     (:assignmentId connect) (assoc :assignment-id (:assignmentId connect))
                     (:paymentAccountId connect) (assoc :payment-account-id (:paymentAccountId connect))))]
    (cond
      (not (gm-config/connect-enabled?))
      {:status 404 :body {:error {:code "connect-disabled" :message "Withdrawals are not enabled"}}}

      (or (not (pos? amount))
          (and requested-id (or (not (string? requested-id))
                                (str/blank? requested-id) (> (count requested-id) 128))))
      {:status 400 :body {:error {:code "invalid-amount"
                                  :message "A positive amount and valid withdrawalId are required"}}}

      (and lookup (not (:ok? lookup)))
      {:status 503 :body {:error {:code "withdrawal-status-unavailable"}}}

      (and existing (not= amount (:amount existing)))
      {:status 409 :body {:error {:code "withdrawal-conflict"}}}

      (and existing (contains? #{"paid" "settling"} (:status existing)))
      {:status 200 :body {:withdrawalId wid :status (:status existing) :amount amount
                          :stripeTransferId (:stripe-transfer-id existing)}}

      (and existing (not (contains? #{"reserved" "reserving"} (:status existing))))
      {:status 409 :body {:withdrawalId wid :status (:status existing)
                          :error {:code "withdrawal-not-retryable"}}}

      (or (str/blank? (:destination record))
          (and (not existing) (not (withdrawal-account-ready? connect roles))))
      {:status 409 :body {:error {:code "payout-account-not-ready"
                                  :message "Payment account is not ready for withdrawals"}}}

      :else
      (let [available (when-not existing
                        (:availableNow (compute-availability
                                        game-name player-name (fetch-wallet-balance game-name player-name)
                                        (quot (System/currentTimeMillis) 1000))))]
        (if (and (not existing) (> amount available))
          {:status 409 :body {:error {:code "exceeds-available"
                                      :message "Amount exceeds funds available to withdraw now"
                                      :availableNow available}}}
          (let [reserve (when-not (= "reserved" (:status existing))
                          (submit-service-event
                           game-name "withdrawal/request" player-name roles
                           (merge (select-keys record [:amount :currency :destination
                                                       :assignment-id :payment-account-id])
                                  {:withdrawal-id wid})))
                saved (get-in (gm-data reserve) [:withdrawal])]
            (if (or (= "reserved" (:status existing)) (effect-succeeded? reserve)
                    (= "reserved" (:status (gm-data reserve))))
              (complete-withdrawal game-name player-name roles (or saved record))
              {:status 409 :body {:error {:code "insufficient-balance"
                                          :message "Ledger reservation failed"}
                                  :withdrawalId wid}})))))))


(defn- fetch-withdrawals
  [game-name player-name]
  (let [resp (submit-service-event game-name "withdrawal/query" player-name ["buyer"] {})
        data (gm-data resp)]
    (or (:withdrawals data) (get data "withdrawals") [])))


(defroutes routes
  (GET "/wallet" [:as request]
    (let [{:keys [context seat response]} (require-selected-seat request)]
      (if response
        response
        (let [game-name (:game-name context)
              player-name (:player-name seat)
              balance (fetch-wallet-balance game-name player-name)
              now-secs (quot (System/currentTimeMillis) 1000)
              {:keys [availableNow pending]} (compute-availability game-name player-name balance now-secs)]
          {:status 200
           :body {:gameId (:game-id context)
                  :selectedSeat (seat-summary seat)
                  :wallet {:currency wallet-currency
                           :balance balance
                           :availableNow availableNow
                           :pending pending}}}))))
  (GET "/api/connect/status" [:as request]
    (let [{:keys [context seat response]} (require-selected-seat request)]
      (if response
        response
        (let [owner-id (:user-id (:identity context))
              connect (fetch-connect-account (:game-name context) owner-id)]
          {:status 200
           :body {:status (or (:status connect) "none")
                  :provider (:provider connect)
                  :accountType (:accountType connect)
                  :maskedExternalId (:maskedExternalId connect)
                  :paymentsEnabled (boolean (:paymentsEnabled connect))
                  :payoutsEnabled (boolean (:payoutsEnabled connect))
                  :sellerEligible (seller-role? seat)
                  :connectEnabled (gm-config/connect-enabled?)}}))))
  (POST "/api/connect/onboard" [:as request]
    (let [{:keys [context seat response]} (require-selected-seat request)]
      (cond
        response response
        (not (gm-config/connect-enabled?))
        {:status 404 :body {:error {:code "connect-disabled"
                                    :message "Withdrawals are not enabled"}}}
        :else
        (let [owner-id (:user-id (:identity context))
              email (:email (:identity context))
              link (create-onboarding-link (:game-name context) owner-id email
                                           (get-in request [:body :return-path]))]
          (if (:ok? link)
            {:status 200 :body {:url (:url link)}}
            {:status 502 :body {:error {:code "onboarding-link-failed"
                                        :details (:error link)}}})))))
  (POST "/api/wallet/withdraw" [:as request]
    (let [{:keys [context seat response]} (require-selected-seat request)]
      (if response
        response
        (request-withdrawal context seat request))))
  (GET "/api/wallet/withdrawals" [:as request]
    (let [{:keys [context seat response]} (require-selected-seat request)]
      (if response
        response
        {:status 200 :body {:withdrawals (fetch-withdrawals (:game-name context)
                                                            (:player-name seat))}})))
)
