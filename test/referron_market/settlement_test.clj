(ns referron-market.settlement-test
  "Shared protocol contract checks for the Referron composition; no real funds."
  (:require [clojure.test :refer [deftest is]] [game.protocols.coseller :as co]))
(def day-ms (* 24 60 60 1000))
(defn- empty-state []
  ;; :referral-override-bps is pinned to 0 here so the market-mechanics settle
  ;; tests below exercise pool/platform splits in isolation; the referral-override
  ;; roll-up (default 500 in production) has its own dedicated tests.
  {:cosellers {}
   :touches []
   :policy {:algorithm "30-days-linear" :pool-share-bps 8000 :referral-override-bps 0 :window-days 30 :history []}
   :settlements {}
   :payouts {}
   :audit-log []})

(defn- settle-payload [event-data co-state market-state]
  {:event/data event-data
   :game-state {:coseller co-state :market market-state}})

(defn- co-after [result] (get-in result [:updated-state :coseller]))

(defn- first-effect-batch [result] (get-in result [:effects 0 :data]))

(defn- finalize-settlement [state checkout-id order-id]
  (co/handle-ledger-settlement-succeeded
   (settle-payload {:player-name "mom"
                    :checkout-id checkout-id
                    :order-id order-id
                    :effect-result {:ok true}}
                   state
                   {})))

(defn- market-with-paid-order
  "A market state with one paid checkout/order. `commission` is the integer
   platform cut in USD."
  [order-id checkout-id buyer capability-id commission paid-at]
  {:orders {order-id {:id order-id
                      :buyer buyer
                      :capability-id capability-id
                      :checkout-id checkout-id
                      :status :paid
                      :paid-at paid-at
                      :commission {:rate-bps 1500
                                   :amount {:amount commission :currency "USD"}
                                   :seller-net {:amount 0 :currency "USD"}}}}
   :checkouts {checkout-id {:id checkout-id
                            :buyer buyer
                            :orders [order-id]
                            :status :paid}}})

(defn- co-state-with-touches [touches]
  (assoc (empty-state) :touches touches))

(defn- co-state-with-policy-and-touches [policy touches]
  (-> (empty-state) (update :policy merge policy) (assoc :touches touches)))

(deftest test-settle-checkout-linear-split
  (let [paid-at 1000000000000
        market (market-with-paid-order "order-1" "checkout-1" "buyer_01" "capability-1" 1000 paid-at)
        ;; c2 was linked to a *different* capability — buyer-level attribution still
        ;; credits them for whatever buyer_01 ultimately checks out.
        co-state (co-state-with-touches
                   [{:coseller "c1" :buyer "buyer_01" :capability-id "capability-1" :ts (- paid-at (* 2 day-ms))}
                    {:coseller "c2" :buyer "buyer_01" :capability-id "some-other-cap" :ts (- paid-at (* 3 day-ms))}])
        r (co/handle-settle-checkout (settle-payload {:player-name "gm" :checkout-id "checkout-1"} co-state market))
        state' (co-after r)
        settlement (get-in state' [:settlements "order-1"])]
    (is (:success r))
    (is (= ["order-1"] (get-in r [:data :pending-ledger])))
    (is (= :pending-ledger (:status settlement)))
    (is (= 800 (:coseller-pool settlement)))          ;; 80% of 1000
    (is (= 800 (:distributed settlement)))
    (is (= 40 (:intergraph-cut settlement)))          ;; 20% of the 200 pre-split platform gross
    (is (= 160 (:platform-retained settlement)))      ;; 1000 - 800 distributed - 40 Intergraph
    (is (= #{"c1" "c2"} (set (:chain settlement))))
    (is (= 400 (get-in settlement [:splits "c1" :amount])))
    (is (= 400 (get-in settlement [:splits "c2" :amount])))
    (is (nil? (get-in state' [:payouts "c1" :totals "USD"])))
    (let [finalized (co-after (finalize-settlement state' "checkout-1" "order-1"))]
      (is (= :settled (get-in finalized [:settlements "order-1" :status])))
      (is (= 400 (get-in finalized [:payouts "c1" :totals "USD"])))
      (is (= 400 (get-in finalized [:payouts "c2" :totals "USD"]))))
    ;; MoM reads :idempotency-key from the effect's TOP LEVEL, not from :data.
    (is (= "settlement:order-1" (get-in r [:effects 0 :idempotency-key])))
    (is (= {:currency "usd-cents"
            :kind "settlement"
            :memo "marketplace settlement order-1 via checkout-1"
            :operations [{:op :debit :player-name "buyer_01" :amount 1000 :reason "buyer-charge"}
                         {:player-name "c1" :amount 400 :reason "coseller-split" :op :mint}
                         {:player-name "c2" :amount 400 :reason "coseller-split" :op :mint}
                         {:account-name "@platform" :amount 160 :reason "platform-cut" :op :mint}
                         {:account-name "@intergraph" :amount 40 :reason "intergraph-cut" :op :mint}]} ;; 200 gross split 160/40
           (first-effect-batch r)))))

(deftest test-settle-checkout-idempotent
  (let [paid-at 1000000000000
        market (market-with-paid-order "order-1" "checkout-1" "buyer_01" "capability-1" 1000 paid-at)
        co-state (co-state-with-touches
                   [{:coseller "c1" :buyer "buyer_01" :capability-id "capability-1" :ts (- paid-at day-ms)}])
        r1 (co/handle-settle-checkout (settle-payload {:player-name "gm" :checkout-id "checkout-1"} co-state market))
        state1 (co-after r1)
        r2 (co/handle-settle-checkout (settle-payload {:player-name "gm" :checkout-id "checkout-1"} state1 market))]
    (is (= ["order-1"] (get-in r1 [:data :pending-ledger])))
    (is (= [] (get-in r2 [:data :settled])))
    (is (= ["order-1"] (get-in r2 [:data :skipped])))
    ;; payout totals wait for MoM's ledger success callback
    (is (nil? (get-in (co-after r2) [:payouts "c1" :totals "USD"])))))

