(ns referron-market.spec-test
  "Guards the handful of spec decisions that fail silently rather than loudly."
  (:require [clojure.test :refer :all]
            [clojure.set :as set]
            [referron-market.spec :as spec]
            [referron-market.handlers :as handlers]
            [game.gm.visitor :as gm-visitor]))

(deftest every-advertised-protocol-has-handlers
  ;; An advertised protocol with no handlers does not fail at boot — it fails
  ;; later, inside a player's request, as an obscure "no handler found".
  (doseq [p (:protocols spec/game-spec)]
    (is (contains? handlers/protocol-registry p)
        (str "protocol '" p "' is advertised to MoM but has no manifest")))
  (is (seq (:gm handlers/merged-manifest))
      "the gm/ lifecycle handlers must survive the merge"))

(deftest market-and-coseller-are-registered-whole
  ;; aow-game narrows coseller to attribution-only with select-keys. Referron Market sells
  ;; things, so the ops that narrowing removes — checkout, settlement — are
  ;; exactly the ones this game needs. If someone later copies aow's narrowing
  ;; here, this fails.
  (let [ops (fn [k] (set (keys (get handlers/merged-manifest k))))]
    (is (contains? (ops :coseller) :settle-checkout)
        "coseller must be whole: Referron Market settles real checkouts, aow-game does not")
    ;; There is no single :checkout op — the flow is create-cart → add-to-cart →
    ;; offers → mark-checkout-paid. Assert the money-moving end of it, which is
    ;; precisely what a catalog-only narrowing would drop.
    (is (contains? (ops :market) :declare-capability)
        "sellers must be able to list")
    (is (contains? (ops :market) :mark-checkout-paid)
        "market must be functional, not catalog-only")
    (is (contains? (ops :market) :mark-fulfilled)
        "digital goods and services fulfil through the same op as physical")))

(deftest audience-contributor-and-coseller-are-self-claimable
  (is (= ["audience" "contributor" "coseller"] (:self-claimable-roles spec/game-spec)))
  (is (= 500 (get-in spec/game-spec [:initial-state :coseller :policy :referral-override-bps]))))

(deftest ui-seed-return-window-never-outlasts-ui-seed-payout-hold
  ;; This checks Referron Market's UI seed only. It does not prove the persisted Market or
  ;; Coseller policies; those require live GM queries before rollout.
  ;;
  ;; Settlement debits an order's whole gross out of @buyer-escrow. After that
  ;; market/issue-refund records :blocked-manual and emits NO ledger effects —
  ;; clawback is impossible because MoM forbids overdraft. So any day on which
  ;; a buyer may open a return but the escrow is already empty is a day of real
  ;; money out with nothing but an operator queue behind it.
  (let [hold (get-in spec/game-spec [:initial-state :coseller :policy :payout-hold-days])
        window (get-in spec/game-spec [:initial-state :market :returns-policy :return-window-days])
        seller-hold (get-in spec/game-spec [:initial-state :coseller :policy :seller-direct-payout-hold-days])
        seller-window (get-in spec/game-spec [:initial-state :market :returns-policy :seller-direct-return-window-days])]
    (is (= 3 hold)
        "the UI seed should display the intended 3-day hold")
    (is (= 3 window)
        "the UI seed should display the intended 3-day return window")
    (is (= 1 seller-hold))
    (is (= 1 seller-window))
    (is (<= seller-window seller-hold))
    (is (<= window hold)
        (str "return-window-days (" window ") must not outlast payout-hold-days ("
             hold ") — every excess day is a return the ledger cannot honour, "
             "landing as :blocked-manual with no ledger movement"))))

(deftest attribution-window-outlives-nothing-and-is-outlived-by-the-cookie
  (let [days (get-in spec/game-spec [:initial-state :coseller :policy :window-days])
        cookie-days (/ gm-visitor/visitor-cookie-max-age 24 60 60)]
    (is (= 180 days)
        "seeding-phase evangelists earn nothing if this is short when commerce opens")
    (is (<= days cookie-days)
        (str "window-days (" days ") must stay under the visitor cookie's "
             cookie-days " days, or chains truncate silently at the far end"))))

(deftest topics-are-a-closed-vocabulary
  (is (= (count spec/topics) (count spec/topic-set))
      "no duplicate topics")
  (is (every? #(re-matches #"[a-z0-9-]+" %) spec/topics)
      "topics are slugs — they appear in /topics/<topic> URLs"))

(deftest brand-bible-is-a-seed-not-the-canon
  ;; /manifesto renders a published essay that elaborates this. If the bible
  ;; grows into full manifesto prose, that decision was reversed — deliberately,
  ;; one hopes, since it moves canon out of the editorial pipeline.
  (is (= #{:name :mission} (set (keys spec/brand-bible))))
  (is (< (count (:mission spec/brand-bible)) 120)
      "the mission is one sentence; the manifesto essay carries the argument"))

(deftest audience-is-the-day-one-seat
  (let [roles (set (map :role (:player-slots spec/game-spec)))]
    (is (contains? roles "audience"))
    (is (contains? roles "contributor"))
    (is (contains? roles "coseller"))
    (is (contains? roles "seller")
        "entities sell here too — e.g. liamco as the seller of record for Liam")
    (is (= 1 (:min (first (filter #(= "strategist" (:role %))
                                  (:player-slots spec/game-spec)))))
        "someone must own the editorial queue")))

(deftest player-slots-speak-the-attention-protocols-vocabulary
  ;; The slots used to read editor/writer/member. Those are better English and
  ;; cannot invoke a single attention op: the protocol gates create-task,
  ;; assign-task and review-task on :strategist, claim-task on :creator,
  ;; and guest pieces on :contributor. A seat named "editor" validates fine in MoM and is then
  ;; refused by every editorial command — the failure lands at publish time,
  ;; far from the spec that caused it. Roles are an interface, not vocabulary
  ;; we get to choose; the display words live in the UI.
  (let [roles (set (map :role (:player-slots spec/game-spec)))]
    (is (contains? roles "strategist") "commissions and approves")
    (is (contains? roles "creator") "claims and drafts")
    (is (contains? roles "contributor") "writes guest pieces under their own byline")
    (is (contains? roles "audience") "can comment and upvote")
    (is (empty? (set/intersection roles #{"editor" "writer" "member"}))
        "the old names cannot come back without breaking attention")))
