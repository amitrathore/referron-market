(ns referron-market.spec
  "Game Specification — the single source of truth for Referron Market's identity,
   active protocols, player slots and seed state.

   The :protocols list does double duty: it decides which handlers are
   dispatchable (see handlers/protocol-registry) and what is advertised to MoM at
   game creation.

   NOTE: :initial-state is NOT applied by MoM at all. MoM stores it on the game
   record (mom.game.domain/create-game) and never reads it back — nothing in
   mom.* resolves :game/initial-state, and the durable :game-state entity starts
   empty and is written only by the router as this GM processes events.

   The seed is applied HERE, by handlers/overlay-state, and only on the UI read
   path (public-state). Durable values win; the seed fills in whatever the game
   has not yet written. So editing this takes effect immediately for any subtree
   the game has never written and needs no drop-and-re-register; it never
   rewrites state the game HAS written; and it does not reach protocol handlers,
   which receive MoM's durable state unseeded and carry their own defaults.

   The older claim here (\"applied by MoM only when the game is FIRST created\")
   described a MoM behaviour that does not exist. See
   mom.game.domain/create-game.")

;; --- Topic vocabulary ---
;;
;; One vocabulary, shared by essays, products and (later) Builds. It is the join
;; that lets an essay about portable memory point at something buyable about
;; portable memory, so it is deliberately concrete rather than editorial —
;; a topic nobody would tag a product with is not pulling its weight.
;;
;; Kept here rather than in game state because it is a compile-time constant the
;; UI validates against; promoting it to state is a later decision, once someone
;; other than an editor needs to add one.

(def topics ["business" "technology" "professional-services" "learning" "community" "products"])

(def topic-set (set topics))

;; --- Market taxonomy ---
;;
;; What the marketplace SELLS, which is a different axis from `topics` (what
;; content and products are ABOUT). An essay about portable memory is tagged
;; `portable-memory`; the thing you buy is a `memory` product.
;;
;; This must be declared. gm-lib's market seeder defaults to THE JS's band
;; taxonomy — Merch, Music, Collabs, #drop — and imposed it on every game
;; declaring `market` until Referron Market shipped with "Music" in a builders' marketplace.
;; Passing our own is the fix; see game.gm.market-seed/seed-market!.

(def market-seed
  {:categories [{:slug "products" :label "Products" :icon "package"}
                {:slug "services" :label "Professional services" :icon "handshake"}
                {:slug "software" :label "Software & AI" :icon "bot"}
                {:slug "learning" :label "Learning" :icon "book-open"}]
   :hashtags [{:slug "new" :label "#new"}]
   :commerce-adapters []})

;; --- Brand bible ---
;;
;; The bible is the SEED, not the canon: {:name :mission}, exactly the shape
;; the-js uses. `/manifesto` renders a published essay that elaborates it, so the
;; movement's argument lives in content — versioned, reviewable and editable
;; through the ordinary editorial pipeline — rather than in a single state field
;; only editors can touch. Keep this to one sentence.

(def brand-bible
  {:name "Referron Market"
   :mission "Turn trusted connections into shared opportunity"})

;; --- Game spec ---

(def game-spec
  {:name "Referron Market"
   :description (str "A referral marketplace for "
                     "products, services, and trusted recommendations.")

   ;; The full the-js protocol set, verbatim. Market ships FUNCTIONAL — real
   ;; listings, real transactions, real commissions — which is why `withdrawal`
   ;; is here too: a market that cannot pay anyone out is a half-protocol.
   ;; Seller agreement is Referron Market's protected acceptance record; commerce remains
   ;; on the shared Market and Coseller protocols.
   :protocols ["shoptype" "attention" "market" "coseller" "withdrawal" "introspection" "avatar" "seller-agreement" "storefront" "discoverability"]
   :protected-protocols ["shoptype" "seller-agreement" "discoverability"]

   ;; Readers choose an audience handle first. They may also write under their
   ;; own byline as contributors or opt into Coselling with the same identity.
   :self-claimable-roles ["audience" "contributor" "coseller"]

   ;; Read by gm-lib's runtime at first boot. Without it the marketplace is
   ;; seeded with another game's vocabulary.
   :market-seed market-seed

   ;; Roles gate OPERATIONS, not status. A role says which protocol ops a seat
   ;; may invoke; it is never a rank and must not be rendered as one. That is how
   ;; a short slot list coexists with "builder" being a broad, self-describing
   ;; identity. Nobody gets "promoted" here.
   ;; These names are the Attention Protocol's vocabulary, not ours to choose.
   ;; attention gates create-task/assign-task/review-task on :strategist and
   ;; claim-task on :creator, while :contributor starts guest pieces. Social ops
   ;; (comment, upvote) are available to readers and writers. The slots here used to
   ;; read editor/writer/member, which is better English and cannot invoke a
   ;; single attention op — a seat would validate fine in MoM and then be refused
   ;; by every editorial command. Renamed to match the-js, which is the working
   ;; reference. "Editor" and "writer" remain the words the UI shows readers.
   :player-slots [{:role "strategist" :min 1 :max nil}  ; runs the editorial queue
                  {:role "creator"    :min 0 :max nil}  ; claims and drafts essays
                  {:role "contributor" :min 0 :max nil} ; starts guest pieces under own byline
                  {:role "audience"   :min 0 :max nil}  ; the default seat on join
                  {:role "buyer"      :min 0 :max nil}
                  {:role "seller"     :min 0 :max nil}  ; incl. entities, e.g. liamco
                  {:role "operator"   :min 0 :max nil}  ; private seller review
                  {:role "coseller"   :min 0 :max nil}
                  {:role "observer"   :min 0 :max nil}]

   :initial-state
   {:attention
    {:brand-bible brand-bible
     :roster {}
     :tasks {}
     :archived-tasks {}
     :calendar []
     :feed []
     :votes {}
     :comments {}
     :research {:status nil :draft-content nil :history []}}

    :market
    {:capabilities {}
     :commerce-adapters {}
     :intents {}
     :offers {}
     :carts {}
     :orders {}
     :disputes {}
     :trust {}
     :categories {}
     :hashtags {}
     ;; These values seed Referron Market's UI read model only. MoM does not apply
     ;; :initial-state to durable game state or GM events. Configure the live
     ;; Market policy with market/set-returns-policy, and inspect paid orders
     ;; before shortening a window.
     ;;
     ;; :return-window-days is how long a buyer may open a return, measured from
     ;; :paid-at. It must never OUTLAST the payout hold: once cosellers settle,
     ;; the order's whole gross has left @buyer-escrow, and market/issue-refund
     ;; then records :blocked-manual with NO ledger effects at all (see
     ;; order-settled? in gm-lib market.clj). Every day the window outlasts the
     ;; hold is a day a buyer can open a return that the ledger cannot honour.
     ;;
     ;; The intended live policy is 3 days for platform-collected orders and
     ;; 1 day for seller-direct software, with matching Coseller holds. A return
     ;; opened inside either window is still open
     ;; when the hold expires, and coseller/hold-reason freezes settlement until
     ;; it resolves — so the safety argument holds without operator cleanup.
     ;;
     ;; :return-response-days 2 (gm-lib defaults to 5) keeps the seller-response
     ;; sweep proportionate to a 3-day window. :post-denial-grace-days is stored
     ;; and validated by gm-lib but never read by anything — do not rely on it.
     :returns-policy {:return-window-days 3
                      :seller-direct-return-window-days 1
                      :return-response-days 2
                      :post-denial-grace-days 0}
     :audit-log []}

    :coseller
    {:cosellers {}
     :signups {}
     :touches []
     ;; :window-days 180 is the one setting here that is unrecoverable if got
     ;; wrong. `30-days-linear` DROPS any touch older than the window, so if
     ;; members share mission content in month one and the marketplace fills out
     ;; in month three, the earliest and most committed evangelists earn nothing.
     ;; Set long now; tighten once commerce is steady.
     ;;
     ;; It must also stay UNDER game.gm.visitor/visitor-cookie-max-age (365d) —
     ;; a touch whose cookie expired first can never be claimed, so the chain
     ;; truncates silently at the far end.
     ;;
     ;; This UI seed does not change the GM's persisted policy. Configure the
     ;; live Coseller policy with coseller/set-policy after auditing paid orders.
     ;; If no durable policy exists, gm-lib uses its own 30-day hold default.
     ;;
     ;; It is safe at 3 ONLY because :return-window-days above is also 3; the
     ;; seller-direct 1-day override similarly matches its return window. The
     ;; hold is what keeps an order's gross in @buyer-escrow, where a refund can
     ;; still be drawn from; settlement empties it, and after that a refund is
     ;; recorded :blocked-manual and moves no money. Change one of these two
     ;; numbers and you must change the other — see the note on :returns-policy.
     ;;
     ;; This bounds returns, not chargebacks. A card dispute can arrive months
     ;; later and lands in the same :blocked-manual operator queue at any hold
     ;; short of ~120 days. That exposure is accepted.
     :policy {:algorithm "30-days-linear"
              :pool-share-bps 8000
              :referral-override-bps 500
              :window-days 180
              :payout-hold-days 3
              :seller-direct-payout-hold-days 1
              :history []}
     :settlements {}
     :payouts {}
     :audit-log []}

    ;; No :withdrawal seed on purpose. withdrawal-state defaults to {} and builds
    ;; its own :records map; the-js omits it for the same reason. Seeding the
    ;; wrong shape here (it is :records, not :requests) would be worse than
    ;; seeding nothing.

    :avatar
    {:avatars {}
     :audit-log []}}})
