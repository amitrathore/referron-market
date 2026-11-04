(ns referron-market.handlers
  "HTTP surface for the Referron Market Game Master.

   Three kinds of route live here and they are worth telling apart:

   - `/v1/gm/handle` — the ONLY route MoM calls. Everything protocol-shaped
     arrives through it and is dispatched against `merged-manifest`.
   - identity + visitor routes — served to a *browser*, proxying MoM. They hold
     no game state and advertise no handlers; the logic is in gm-lib
     (`game.gm.identity`, `game.gm.visitor`) and shared with the other GMs.
   - `/ui/*` — read-only projections for the site.

   Referron Market adds no protocol of its own, so there is no `protocols/` package here yet."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [compojure.core :refer [defroutes GET POST OPTIONS]]
            [compojure.route :as route]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [ring.middleware.json :as json-middleware]
            [ring.middleware.cookies :as cookies]
            [game.gm.config :as gm-config]
            [game.gm.dispatch :as gm-dispatch]
            [game.gm.identity :as gm-identity]
            [game.gm.runtime :as gm-runtime]
            [game.gm.v2 :as gm-v2]
            [game.gm.visitor :as gm-visitor]
            [game.protocols.attention :as attention]
            [game.protocols.avatar :as avatar]
            [game.protocols.coseller :as coseller]
            [game.protocols.introspection :as introspection]
            [game.protocols.market :as market]
            [game.protocols.shoptype :as shoptype]
            [game.gm.shoptype :as shoptype-http]
            [referron-market.seller-connect :as shoptype-connect]
            [game.protocols.withdrawal :as withdrawal]
            [referron-market.spec :as spec]
            [referron-market.marketplace :as marketplace]
            [referron-market.wallet :as wallet]
            [referron-market.seller-agreement :as seller-agreement]
            [referron-market.commercial-schedule :as commercial]
            [referron-market.storefront :as storefront]
            [referron-market.discoverability :as discovery]
            [game.protocols.discoverability :as discovery-protocol]
            [referron-market.seller-commerce :as seller-commerce]))

;; --- Logging ---

(defn- log! [level msg & [data]]
  (if data
    (println (str " [" level "] " msg) (pr-str data))
    (println (str " [" level "] " msg))))

;; --- MoM plumbing ---

(defn- current-game-name []
  (some-> @gm-runtime/game-name-handle str))

(defn- submit-ui-event
  "Dispatch an event to MoM as the CALLER, forwarding their token so MoM
   re-verifies the human rather than trusting this backend."
  [request game-name seat]
  (let [body (or (:body request) {})
        roles (vec (or (:player-roles seat)
                       (some-> (:player-role seat) vector)
                       []))
        event-data (-> (or (:event/data body) {})
                       (assoc :player-name (:player-name seat))
                       (cond-> (seq roles)
                         (assoc :roles (mapv str roles))))
        payload {:event/type (str (:event/type body))
                 :event/data event-data
                 :caller-player-name (str (:player-name seat))
                 :game-name (str game-name)}]
    (http/post (str (gm-identity/mom-url nil) "/api/v1/action")
               (gm-identity/request-user-auth
                 request
                 {:content-type :json
                  :body (json/generate-string payload)
                  :as :json
                  :throw-exceptions false}))))

;; --- GM lifecycle handler ---

(defn- handle-player-join
  "Acknowledges a join and records the member signup for referral attribution.
   If they arrived through a coseller's ?ref= link — including anonymously, via
   a visitor id later claimed — that first touch becomes the referral edge."
  [payload]
  (let [player-id   (get-in payload [:event/data :player-id])
        player-role (get-in payload [:event/data :player-role])
        signup      (coseller/handle-player-join payload)]
    (cond-> {:success true}
      player-id   (assoc :player-id (str player-id))
      player-role (assoc :player-role (str player-role))
      (:updated-state signup) (assoc :updated-state (:updated-state signup))
      (seq (:events signup))  (assoc :events (:events signup)))))

(def gm-manifest
  {:gm {:player-join
        {:handler handle-player-join
         :type :lifecycle
         :doc (str "Acknowledges a player join and records the member signup for "
                   "referral attribution — if they arrived via a coseller's ?ref= "
                   "link, that first touch becomes the referral edge returned by "
                   "coseller/query-referrals.")
         :summary "Confirm the player role on join and record the signup."
         :when-to-use "Fired by MoM when a player claims a seat. Not called directly."
         :roles #{:any}
         :input [:map]
         :output [:map [:player-role {:optional true} :string]]
         :errors []
         :examples [{:request {:event/type "gm/player-join"
                               :event/data {:player-name "ari" :player-role "audience"}}
                     :response {:success true :player-role "audience"}}]}

        ;; MoM's GET /admin/games/<name>/state routes gm/query-state through the
        ;; GM. Without a handler that route 400s — the state is fine, the GM just
        ;; has nothing to answer with. gm-lib does not supply one; the-js defines
        ;; its own, so Referron Market does too.
        :query-state
        {:handler (fn [payload]
                    (if (and (:caller payload)
                             (not (contains? #{"system" "owner" :system :owner} (get-in payload [:caller :type]))))
                      {:success false :status 403 :error {:code "private-state-forbidden"}}
                      {:success true :data {:game-state (:game-state payload)}}))
         :type :query
         :doc "Returns the full game-state snapshot exactly as provided in webhook context."
         :summary "Read current materialized state."
         :when-to-use "Diagnostics, UI hydration, external observer sync."
         :roles #{:any}
         :input [:map]
         :output [:map [:data [:map [:game-state :map]]]]
         :errors []
         :examples [{:request {:event/type "gm/query-state" :event/data {}}
                     :response {:success true :data {:game-state {}}}}]}}})

;; --- Manifest ---
;;
;; Every protocol Referron Market could dispatch. Only the names listed in the game spec's
;; :protocols end up in merged-manifest, so this map may safely be wider than the
;; spec — but never narrower, or an advertised protocol silently has no handlers.
;;
;; Unlike aow-game, coseller and market are registered WHOLE rather than narrowed
;; with select-keys: Referron Market sells things, so the settlement and checkout operations
;; those GMs omit are exactly the ones this game needs.

(def protocol-registry
  {"attention"     attention/manifest
   "market"        market/manifest
   "shoptype"      (shoptype/manifest)
   "coseller"      coseller/manifest
   "withdrawal"    withdrawal/manifest
   "introspection" introspection/manifest
   "avatar"        avatar/manifest
   "seller-agreement" seller-agreement/manifest
   "storefront" storefront/manifest
   "discoverability" discovery-protocol/manifest})

(def merged-manifest
  (let [names   (:protocols spec/game-spec)
        unknown (remove protocol-registry names)]
    (when (seq unknown)
      ;; Loud, because an advertised protocol with no handlers fails later as an
      ;; obscure "no handler found" deep inside a player's request.
      (println " [GM] WARNING: unknown protocols in game-spec:" (vec unknown)))
    (merge (apply merge (keep protocol-registry names))
           gm-manifest)))

;; --- Dispatcher ---

(defn- shoptype-context [payload]
  {:enabled (shoptype-http/enabled?)
   :environment (or (System/getenv "SHOPTYPE_ENVIRONMENT") "preview")
   :requirements [{:kind "agreement" :url "/seller-agreement"}
                  {:kind "commercial-terms" :url "/seller-agreement"}
                  {:kind "operator-activation" :url "/operator/sellers"}]
   :payment-routes ["seller-direct"]
   :sellers (into {} (for [application (seller-agreement/applications (:game-state payload))
                          :let [player (:player-name application)
                                accepted (commercial/activation-ready? (:game-state payload) (:id application))
                                schedule (commercial/current (:game-state payload) (:id application))]]
                      [player {:application-id (:id application) :status (:status application)
                               :commercial-accepted (boolean accepted)
                               :commission-bps (get-in schedule [:terms :commission-bps])
                               :terms-hash (shoptype-http/terms-hash (:terms schedule))
                               :activation-ready (and (contains? (if (= "production" (System/getenv "SHOPTYPE_ENVIRONMENT")) #{"activated"} #{"approved" "activated"}) (:status application)) (boolean accepted))}]))})

(declare overlay-state)
(defn dispatch [payload]
  (let [event-type (:event/type payload)]
    (log! "INFO" "Event received" {:event/type event-type})
    (let [payload (if (str/starts-with? (str event-type) "shoptype/")
                    (assoc payload :shoptype/context (shoptype-http/enrich-payment-readiness payload (shoptype-context payload))) payload)
          payload (if (str/starts-with? (str event-type) "discoverability/")
                    (assoc payload :discoverability/context
                           (discovery/context (overlay-state (:initial-state spec/game-spec) (:game-state payload))))
                    payload)
          result (gm-dispatch/dispatch-gm-handler payload merged-manifest)]
      (if (:success result)
        (log! "INFO" "Event handled"
              {:event/type event-type
               :status (or (:status result) 200)
               :updated-state-keys (->> (keys (or (:updated-state result) {}))
                                        (map name) sort vec)})
        (log! "WARN" "Event failed"
              {:event/type event-type :error (:error result)}))
      result)))

(defn- mom-webhook-authorized?
  [request]
  (let [expected (gm-config/resolve-mom-api-token)
        presented (some-> (gm-identity/auth-header (:headers request))
                          (str/replace #"^Bearer " ""))]
    (or (str/blank? (str expected))
        (= (str expected) (str presented)))))

;; --- Public read model ---

(defn- with-service-auth [req]
  (if-let [headers (gm-config/mom-auth-headers)]
    (assoc req :headers headers)
    req))

(declare fetch-game-state-from-mom)

(defn- fetch-game-state
  "The game's durable state. On runtime v2 (GM_RUNTIME=v2) that is the GM's own store (nil
   while it is still starting, which callers already treat as unavailable); otherwise MoM's."
  [request game-name]
  (if (gm-v2/enabled?)
    (gm-v2/read-game-state)
    (fetch-game-state-from-mom request game-name)))

(defn- fetch-game-state-from-mom
  "Durable game state from MoM. Service auth when the GM holds a credential,
   otherwise the caller's — an anonymous reader still needs to see essays."
  [request game-name]
  (try
    (let [base {:as :json :throw-exceptions false}
          req (if (gm-config/resolve-mom-game-token)
                (with-service-auth base)
                (gm-identity/request-user-auth request base))
          resp (http/get (str (gm-identity/mom-url nil)
                              "/api/v1/storage/game/" game-name "/reload")
                         req)]
      (when (and (= 200 (:status resp)) (map? (:body resp)) (not= false (get-in resp [:body :success])))
        (:body resp)))
    (catch Exception e
      (log! "ERROR" "UI state reload failed" {:error (.getMessage e)})
      nil)))

(defn- overlay-state
  "Merge durable state onto the seeded initial state so a sparse or
   freshly-created game still renders its seeded content. nil in the durable
   state means 'missing', not 'empty'."
  [seeded durable]
  (cond
    (nil? durable) seeded
    (and (map? seeded) (map? durable))
    (reduce-kv (fn [m k v] (assoc m k (overlay-state (get seeded k) v)))
               seeded durable)
    :else durable))

(defn- effective-state
  [request game-id game-name]
  (let [state (fetch-game-state request game-name)
        payload (if (and (map? state) (:success state) (contains? state :state))
                  (:state state)
                  state)
        effective (when (map? state) (overlay-state (:initial-state spec/game-spec) payload))]
    (when effective
      {:gameId (str game-id)
       :topics spec/topics
       :gameState effective})))

(defn public-game-state
  "The anonymous read model. Publishing the entire durable game state leaked
   referral policy, signups, touches, drafts, orders and audit trails. The site
   only needs published editorial content and catalog capabilities."
  [effective]
  {:storefront {:profiles (storefront/public-profiles effective)}
   :attention (select-keys (:attention effective) [:brand-bible :feed])
   :market {:capabilities (into {}
                               (map (fn [[id capability]]
                                      [id (select-keys capability
                                                       [:id :seller :title :description :kind
                                                        :external-ref :terms :commerce
                                                        :entitlements :category-ids
                                                        :hashtag-ids :images :checkout
                                                        :status :display-order
                                                        :created-at :updated-at])]))
                               (get-in effective [:market :capabilities] {}))}})

(defn- public-state
  [request game-id game-name]
  (when-let [state (effective-state request game-id game-name)]
    (update state :gameState public-game-state)))

(defn- state-entry
  [m id]
  (some (fn [[k v]]
          (when (= (str/lower-case (name k))
                   (str/lower-case (str id)))
            v))
        (or m {})))

(defn- active-capability?
  [capability]
  (= "active" (-> (or (:status capability) "active") name str/lower-case)))

(defn- commerce-adapter-id
  [capability]
  (or (get-in capability [:commerce :adapter])
      (get-in capability [:commerce "adapter"])))

(defn coseller-program
  "Build the private program projection for one activated Coseller. Numeric
   economics never enter the anonymous catalog response."
  [effective player-name app-base-url]
  (let [coseller (state-entry (get-in effective [:coseller :cosellers]) player-name)
        policy (get-in effective [:coseller :policy])
        capabilities (vals (get-in effective [:market :capabilities] {}))
        opportunities (->> capabilities
                           (filter active-capability?)
                           (keep (fn [capability]
                                   (let [adapter (commerce-adapter-id capability)
                                         bps (seller-commerce/commission-bps adapter)]
                                     (when (and adapter bps)
                                       {:capability-id (str (:id capability))
                                        :title (:title capability)
                                        :seller (str (:seller capability))
                                        :adapter (str adapter)
                                        :commission-bps bps}))))
                           (sort-by (juxt :seller :title))
                           vec)
        base (str/replace (str app-base-url) #"/+$" "")]
    {:status (if coseller "active" "needs-registration")
     :player-name (str player-name)
     :ref-token (:ref-token coseller)
     :share-url (when-let [token (:ref-token coseller)]
                  (str base "/?ref=" token))
     :market-status (get-in discovery/defaults [:coseller-readiness :market-status])
     :earnings-enabled (get-in discovery/defaults [:coseller-readiness :earnings-enabled])
     :commission-range {:wording (if (seq opportunities)
                                   "Vary by offering; see current terms below"
                                   "Are not available until sellers activate their offerings")}
     :policy {:algorithm (:algorithm policy)
              :window-days (:window-days policy)
              :payout-hold-days (:payout-hold-days policy)
              :seller-direct-payout-hold-days (:seller-direct-payout-hold-days policy)
              :pool-share-bps (:pool-share-bps policy)
              :referral-override-bps (or (:referral-override-bps policy) 500)
              :withdrawal-minimum nil
              :subscription-chain "locked-at-start"}
     :opportunities opportunities}))

(defn- result-body
  [response]
  (let [body (:body response)]
    (or (:gm-result body) (get body "gm-result") body)))

(defn- activation-response
  [request context seat]
  (let [roles (set (map str (or (:player-roles seat)
                                (some-> (:player-role seat) vector)
                                [])))
        role-response (when-not (contains? roles "coseller")
                        (gm-identity/claim-membership-response
                         (assoc request :body {:player-name (:player-name seat)
                                               :roles ["coseller"]})
                         (:game-name context)))
        role-ok? (or (nil? role-response)
                     (<= 200 (:status role-response) 299))]
    (if-not role-ok?
      role-response
      (let [coseller-seat (assoc seat
                                 :player-roles (vec (conj roles "coseller"))
                                 :player-role "coseller")
            registration (submit-ui-event
                          (assoc request :body {:event/type "coseller/register"
                                                :event/data {}})
                          (:game-name context)
                          coseller-seat)
            result (result-body registration)]
        (if (and (<= 200 (:status registration) 299)
                 (true? (or (:success result) (get result "success"))))
          {:status 200
           :body {:status "active"
                  :player-name (str (:player-name seat))
                  :ref-token (or (get-in result [:data :ref-token])
                                 (get-in result ["data" "ref-token"]))}}
          {:status (or (:status registration) 502)
           :body result})))))

;; --- Identity ---

(defn- identity-opts []
  {:game-id (some-> @gm-runtime/game-id str)
   :game-name (current-game-name)
   :app-base-url (gm-config/resolve-app-base-url)
   ;; Day one is "share the manifesto", so anyone who signs in gets a seat as
   ;; `audience`, and may self-claim `coseller` to get a share link without
   ;; re-authenticating. Both are configured on the MoM side
   ;; (onboarding-role / self-claimable-roles); listed here as the roles that
   ;; unlock the editorial surface.
   ;;
   ;; These are the Attention Protocol's role names, not display labels — see
   ;; spec/player-slots. `audience` rather than `member` specifically because
   ;; attention gates comment and upvote on :audience/:creator/:strategist, so a
   ;; `member` seat could not even comment on the feed.
   :gated-roles #{"strategist" "creator"}
   ;; Required BECAUSE there is no :ensure-membership-fn below. gm-lib only
   ;; defaults a selected seat from the onboarding path, so without this a
   ;; reader who owns exactly one seat got seats in /session and selectedSeat
   ;; nil — the UI read selectedSeat, saw nothing, and asked them to claim a
   ;; handle they already had. Claiming again quietly resolved to the same
   ;; membership, so it looked like the button did nothing.
   :select-sole-seat? true})
   ;; DELIBERATELY no :ensure-membership-fn. Passing ensure-audience here enrols
   ;; a signed-in visitor automatically — under a GENERATED handle like
   ;; aud-8253156e0ee641749af98a0e4a13. MoM has no rename (player-name is the
   ;; join key across protocol state and the ledger account key), so that handle
   ;; is permanent and becomes their public byline on every essay they publish.
   ;;
   ;; Enrolment is the one moment the name can be chosen, so it is not spent
   ;; silently on the reader's behalf. /join/claim below lets them pick, and a
   ;; signed-in visitor with no seat is a legitimate state until they do.

(defn- session-context [request]
  (gm-identity/session-context request (identity-opts)))

(defn- player-token-response [request]
  (let [{:keys [context seat response]}
        (gm-identity/require-selected-seat request (identity-opts))
        no-store {"Cache-Control" "no-store, max-age=0"}]
    (if response
      (assoc response :headers no-store)
      (try
        (let [upstream (http/post
                        (str (gm-identity/mom-url nil) "/auth/player-token")
                        (gm-identity/request-user-auth
                         request
                         {:content-type :json
                          :body (json/generate-string
                                 {:game-name (:game-name context)
                                  :caller-player-name (:player-name seat)})
                          :as :json
                          :throw-exceptions false}))
              body (:body upstream)
              status (:status upstream)]
          (cond
            (and (= 200 status) (not (str/blank? (:token body))))
            {:status 200 :headers no-store
             :body {:token (:token body)
                    :playerName (:player-name seat)
                    :expiresAt (:expires-at body)
                    :expiresIn (:expires-in body)}}

            (#{400 401 403} status)
            {:status status :headers no-store
             :body {:error {:code (or (:error body) "token-unavailable")
                            :message (or (:message body) "Could not create a seat token.")}}}

            :else
            {:status 502 :headers no-store
             :body {:error {:code "token-service-unavailable"
                            :message "Could not create a seat token right now. Try again."}}}))
        (catch Exception _
          {:status 502 :headers no-store
           :body {:error {:code "token-service-unavailable"
                          :message "Could not create a seat token right now. Try again."}}})))))

;; --- Routes ---

(defn- html-escape
  [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- find-feed-item
  "The published item at `key`, matched by slug OR id.

   Mirrors the UI's findPost. The id branch is not redundant: the first post was
   published before slugs existed and its UUID URL is already shared."
  [state key]
  (let [feed (get-in state [:gameState :attention :feed])]
    (some (fn [it]
            (let [slug (or (:slug it) (get-in it [:payload :slug]) (:id it))]
              (when (or (= (str slug) (str key)) (= (str (:id it)) (str key)))
                it)))
          feed)))

(defn- og-block
  [{:keys [title description image url]}]
  (str "<!-- og:start -->\n"
       "<meta property=\"og:site_name\" content=\"Referron Market\">\n"
       "<meta property=\"og:type\" content=\"article\">\n"
       "<meta property=\"og:title\" content=\"" (html-escape title) "\">\n"
       (when description
         (str "<meta property=\"og:description\" content=\"" (html-escape description) "\">\n"))
       (when url
         (str "<meta property=\"og:url\" content=\"" (html-escape url) "\">\n"))
       (if image
         (str "<meta property=\"og:image\" content=\"" (html-escape image) "\">\n"
              "<meta name=\"twitter:card\" content=\"summary_large_image\">\n"
              "<meta name=\"twitter:image\" content=\"" (html-escape image) "\">\n"
              "<meta property=\"og:image:alt\" content=\"" (html-escape title) "\">\n")
         "<meta name=\"twitter:card\" content=\"summary\">\n")
       "<meta name=\"twitter:title\" content=\"" (html-escape title) "\">\n"
       (when description
         (str "<meta name=\"twitter:description\" content=\"" (html-escape description) "\">\n"))
       "<!-- og:end -->"))

(defn- essay-response
  "index.html with og: tags describing THIS post rather than the site.

   Referron Market is client-rendered, so without this every shared essay unfurls as the
   homepage on X, LinkedIn and Slack — the exact surface a coseller link lives
   in, and the one place a featured image earns its keep.

   Falls back to the plain static file on ANY failure: state unavailable, item
   not found, or an exception. An unfurl is never worth a 502 on the article."
  ([request slug]
   (essay-response request slug (str "/essays/" slug)))
  ([request slug public-path]
   (or (try
         (when-let [tpl (some-> (io/resource "ui/index.html") slurp)]
           (when-let [state (public-state request
                                          (some-> @gm-runtime/game-id str)
                                          (current-game-name))]
             (when-let [it (find-feed-item state slug)]
               (let [p (:payload it)
                     base (or (gm-config/resolve-app-base-url)
                              (gm-identity/request-base-url request))
                     block (og-block {:title (or (:title p) "Referron Market")
                                      :description (or (:headline p) (:summary p)
                                                       "Discover products and services worth sharing. Make trusted recommendations, open new doors, and earn from the value you help create.")
                                      :image (let [image (:featured_image p)]
                                               (if (str/blank? image)
                                                 (str base "/assets/brand/social-card-v2.png")
                                                 (str (.resolve (java.net.URI. (str base "/")) image))))
                                      :url (str base public-path)})]
                 (-> (str/replace tpl
                                  #"(?s)<!-- og:start -->.*?<!-- og:end -->"
                                  (str/re-quote-replacement block))
                     (as-> body {:status 200
                                 :headers {"Content-Type" "text/html; charset=utf-8"
                                           ;; Same no-store as ui-file-response: a
                                           ;; cached shell strands readers on an
                                           ;; old build, and crawlers refetch.
                                           "Cache-Control" "no-store, max-age=0"}
                                 :body body}))))))
         (catch Exception e
           (log! "WARN" "og render failed; serving static shell"
                 {:slug slug :error (.getMessage e)})
           nil))
       (gm-identity/ui-file-response "index.html"))))

(defn- commercial-event-response [request operation operator?]
  (let [{:keys [context seat response]} (gm-identity/require-selected-seat request (identity-opts))
        roles (set (map str (or (:player-roles seat) (some-> (:player-role seat) vector))))]
    (or response
        (when (and operator? (not (contains? roles "operator")))
          {:status 403 :body {:error {:code "operator-required"}}})
        (let [submitted (submit-ui-event (assoc request :body {:event/type (str "seller-agreement/" operation)
                                                              :event/data (:body request)}) (:game-name context) seat)
              result (result-body submitted)]
          (if (and (<= 200 (or (:status submitted) 502) 299) (true? (:success result)))
            {:status 200 :headers {"Cache-Control" "no-store"} :body (:data result)}
            {:status (or (:status result) (when-not (<= 200 (or (:status submitted) 502) 299) (:status submitted)) 422)
             :body {:error (or (:error result) {:code "commercial-terms-failed"})}})))))

(defn- discovery-snapshot [request]
  (some-> (effective-state request (some-> @gm-runtime/game-id str) (current-game-name)) :gameState discovery/snapshot))
(defn- discovery-response [request path artifact?]
  (if-let [snapshot (discovery-snapshot request)]
    (or ((if artifact? discovery/artifact-response discovery/render-page) snapshot path)
        {:status 404 :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"}
         :body "<!doctype html><html lang=\"en\"><title>Not found — Referron Market</title><main><h1>Not found</h1><p>This public page does not exist.</p><a href=\"/\">Home</a></main></html>"})
    {:status 503 :headers {"Cache-Control" "no-store" "Retry-After" "30"}
     :body {:error {:code "state-unavailable" :message "Public content is temporarily unavailable. Please try again."}}}))
(defn- operator-guard [request]
  (let [{:keys [seat response] :as access} (gm-identity/require-selected-seat request (identity-opts))]
    (assoc access :response (or response (when-not (some #{"operator" :operator} (:player-roles seat))
                                         {:status 403 :body {:error {:code "operator-required"}}})))))
(defn- discovery-operation [request operation]
  (let [{:keys [context seat response]} (operator-guard request)]
    (or response
        (let [submitted (submit-ui-event
                         (assoc request :body {:event/type (str "discoverability/" operation)
                                               :event/data (if (map? (:body request)) (:body request) {})})
                         (:game-name context) seat)
              result (result-body submitted)]
          {:status (if (:success result) 200 (or (:status result) 502))
           :headers {"Cache-Control" "no-store"} :body result}))))
(defn- private-page [file]
  (assoc-in (gm-identity/ui-file-response file) [:headers "X-Robots-Tag"] "noindex, nofollow"))
(defn- sync-discovery-registration [request]
  (let [{:keys [context response]} (operator-guard request)]
    (or response
        (try
          (let [base (gm-identity/mom-url nil)
                options (gm-identity/request-user-auth request {:as :json :throw-exceptions false})
                listed (http/get (str base "/admin/games") options)
                body (:body listed)
                games (if (sequential? body) body (:games body))
                existing (some #(when (= (:game-name context) (:game/game-name %)) %) games)]
            (if (and (= 200 (:status listed)) (:game/host-id existing) (:game/id existing))
              (let [payload {:host-id (:game/host-id existing) :game-name (:game-name context)
                             :name (:game/name existing)
                             :protocols (vec (distinct (concat (:protocols spec/game-spec) (:game/protected-protocols existing))))
                             :protected-protocols (vec (distinct (concat (:game/protected-protocols existing) (:protected-protocols spec/game-spec))))}
                    result (http/post (str base "/admin/games")
                                      (merge options {:content-type :json :body (json/generate-string payload)}))
                    record (:body result)
                    verified? (and (<= 200 (:status result) 299)
                                   (= (:game/id existing) (:game/id record))
                                   (every? (set (:game/protected-protocols record)) (:protected-protocols spec/game-spec)))]
                (if verified?
                  {:status 200 :headers {"Cache-Control" "no-store"}
                   :body {:success true :protocols (:game/protocols record) :protected-protocols (:game/protected-protocols record)}}
                  {:status (if (= 403 (:status result)) 403 502)
                   :body {:error {:code "registration-sync-failed"
                                  :message "MoM requires a signed-in platform administrator to reconcile this game's protocol declaration."}}}))
              {:status 503 :body {:error {:code "registration-unavailable" :message "The existing game registration could not be verified; nothing was changed."}}}))
          (catch Exception _
            {:status 502 :body {:error {:code "registration-sync-unavailable" :message "Could not reconcile the game registration. Try again."}}})))))

(defn- shoptype-options []
  {:enabled? (shoptype-http/enabled?)
       :market-id (or (System/getenv "SHOPTYPE_MARKET_ID") "referron-market")
       :game-id (some-> @gm-runtime/game-id str) :game-name (current-game-name)
       :environment (or (System/getenv "SHOPTYPE_ENVIRONMENT") "preview")
       :billing-modes ["one-time"] :payment-routes ["seller-direct"]
       :handlers (shoptype-connect/shoptype-handlers {:game-id (some-> @gm-runtime/game-id str) :game-name (some-> @gm-runtime/game-name-handle str) :select-sole-seat? true})})

(defroutes gm-routes
  wallet/routes
  (GET "/shoptype/v0.1/manifest" []
    (shoptype-http/manifest-response (shoptype-options)))
  (POST "/shoptype/v0.1/operations" [:as request]
    (shoptype-http/operation-response request (shoptype-options)))
  marketplace/routes
  (GET "/health" [] {:status 200 :body {:ok true :game (current-game-name)}})

  (GET "/api/operator/discoverability" [:as request]
    (discovery-operation request "query-status"))
  (POST "/api/operator/discoverability/sync-registration" [:as request]
    (sync-discovery-registration request))
  (POST "/api/operator/discoverability/:operation" [operation :as request]
    (if (#{"update-profile" "submit-narrative" "review-narrative" "audit"} operation)
      (discovery-operation request operation)
      {:status 404 :body {:error {:code "operation-not-found"}}}))

  (GET "/auth.json" [:as request]
    (let [base (str/replace (or (gm-config/resolve-app-base-url)
                               (gm-identity/request-base-url request)) #"/+$" "")]
      (assoc-in (gm-identity/auth-config-response request (identity-opts))
                [:body :logo_url] (str base "/assets/brand/referron-logo.png"))))
  (GET "/auth/callback" [:as request]
    (gm-identity/ui-redirect-with-query request (identity-opts)))
  (GET "/auth/success" [:as request]
    (gm-identity/ui-redirect-with-query request (identity-opts)))
  (GET "/auth/failure" [:as request]
    (gm-identity/ui-redirect-with-query request (identity-opts)))

  ;; The hosted auth app POSTs here — to the GM's own origin, never to MoM
  ;; directly, since it only knows the URLs in /auth.json. Referron Market shipped without
  ;; these, so the app fetched a 404 and reported "Failed to fetch
  ;; (status=network)", which reads like CORS or DNS rather than a missing
  ;; route. Both bodies live in gm-lib; the-js delegates to the same ones.
  (OPTIONS "/auth/login" []
    (gm-identity/cors-preflight-response))
  (POST "/auth/login" [:as request]
    (gm-identity/login-response request))
  (GET "/auth/me" [:as request]
    (gm-identity/auth-response request))

  ;; Enrol under a name the person chose. Separate from sign-in on purpose:
  ;; MoM derives the owner from the caller's verified token, so this must run
  ;; as them, and it cannot be done for them at session assembly.
  (OPTIONS "/join/claim" []
    (gm-identity/cors-preflight-response))
  (POST "/join/claim" [:as request]
    (gm-identity/claim-membership-response request (current-game-name)))

  (GET "/api/seller/agreement" []
    (if-let [agreement (seller-agreement/current-agreement)]
      {:status 200 :headers {"Cache-Control" "no-store"} :body agreement}
      {:status 503 :body {:error {:code "agreement-unavailable"}}}))

  (GET "/api/seller/commercial-schedule" [:as request]
    (let [{:keys [context seat response]} (gm-identity/require-selected-seat request (identity-opts))]
      (or response
          (if-let [state (effective-state request (:game-id context) (:game-name context))]
            (let [summary (commercial/seller-summary (:gameState state) (:player-name seat))]
              {:status 200 :headers {"Cache-Control" "no-store"}
               :body (if (and (:schedule summary) (not= (or (:membership-id seat) (:seat-id seat)) (get-in summary [:schedule :membership-id])))
                       {:schedule nil :acceptance nil} summary)})
            {:status 502 :body {:error {:code "state-unavailable"}}}))))

  (POST "/api/seller/commercial-schedule/accept" [:as request]
    (commercial-event-response request "accept-commercial-schedule" false))
  (POST "/api/operator/sellers/commercial-schedule" [:as request]
    (commercial-event-response request "save-commercial-draft" true))
  (POST "/api/operator/sellers/commercial-schedule/publish" [:as request]
    (commercial-event-response request "publish-commercial-schedule" true))

  (GET "/api/seller/agreement/status" [:as request]
    (let [{:keys [context seat response]}
          (gm-identity/require-selected-seat request (identity-opts))]
      (or response
          (if-let [state (effective-state request (:game-id context) (:game-name context))]
            {:status 200
             :body {:acceptance (seller-agreement/latest-acceptance
                                 (:gameState state) (:player-name seat))}}
            {:status 502 :body {:error {:code "state-unavailable"}}}))))

  (POST "/api/seller/agreement/accept" [:as request]
    (let [{:keys [context seat response]}
          (gm-identity/require-selected-seat request (identity-opts))]
      (or response
          (let [submitted (submit-ui-event
                           (assoc request :body {:event/type "seller-agreement/accept"
                                                 :event/data (:body request)})
                           (:game-name context) seat)
                result (result-body submitted)
                success? (true? (or (:success result) (get result "success")))
                acceptance (or (get-in result [:data :acceptance])
                               (get-in result ["data" "acceptance"]))]
            (if (and (<= 200 (or (:status submitted) 502) 299) success? acceptance)
              {:status 200 :body {:acceptance acceptance}}
              {:status (or (:status result)
                           (when-not (<= 200 (or (:status submitted) 502) 299)
                             (:status submitted))
                           422)
               :body {:error (or (:error result) (get result "error")
                                 {:code "agreement-acceptance-failed"})}})))))

  (POST "/api/seller/storefront" [:as request]
    (let [{:keys [context seat response]}
          (gm-identity/require-selected-seat request (identity-opts))]
      (or response
          (let [submitted (submit-ui-event
                           (assoc request :body {:event/type "storefront/update-profile"
                                                 :event/data (:body request)})
                           (:game-name context) seat)
                result (result-body submitted)
                profile (get-in result [:data :profile])]
            (if (and (= 200 (:status submitted)) (:success result) profile)
              {:status 200 :headers {"Cache-Control" "no-store"} :body {:profile profile}}
              {:status (or (:status result) (when-not (= 200 (:status submitted)) (:status submitted)) 422)
               :body {:error (or (:error result) {:code "storefront-update-failed"})}})))))

  (GET "/api/operator/sellers" [:as request]
    (let [{:keys [context seat response]}
          (gm-identity/require-selected-seat request (identity-opts))
          roles (set (map str (or (:player-roles seat)
                                  (some-> (:player-role seat) vector))))]
      (or response
          (when-not (contains? roles "operator")
            {:status 403 :body {:error {:code "operator-required"}}})
          (if-let [state (effective-state request (:game-id context) (:game-name context))]
            {:status 200 :headers {"Cache-Control" "no-store"}
             :body {:applications (mapv #(assoc % :commercial (commercial/operator-summary (:gameState state) %))
                                        (seller-agreement/applications (:gameState state)))}}
            {:status 502 :body {:error {:code "state-unavailable"}}}))))

  (POST "/api/operator/sellers/review" [:as request]
    (let [{:keys [context seat response]}
          (gm-identity/require-selected-seat request (identity-opts))
          roles (set (map str (or (:player-roles seat)
                                  (some-> (:player-role seat) vector))))]
      (or response
          (when-not (contains? roles "operator")
            {:status 403 :body {:error {:code "operator-required"}}})
          (let [submitted (submit-ui-event
                           (assoc request :body {:event/type "seller-agreement/review"
                                                 :event/data (:body request)})
                           (:game-name context) seat)
                result (result-body submitted)
                acceptance (or (get-in result [:data :acceptance])
                               (get-in result ["data" "acceptance"]))]
            (if (and (<= 200 (or (:status submitted) 502) 299)
                     (true? (or (:success result) (get result "success"))) acceptance)
              {:status 200 :headers {"Cache-Control" "no-store"}
               :body {:acceptance acceptance}}
              {:status (or (:status result)
                           (when-not (<= 200 (or (:status submitted) 502) 299)
                             (:status submitted)) 422)
               :body {:error (or (:error result) (get result "error")
                                 {:code "review-failed"})}})))))

  (GET "/session" [:as request]
    {:status 200 :body (gm-identity/session-response-body (session-context request))})

  (GET "/seats" [:as request]
    (let [{:keys [context response]} (gm-identity/require-game-context request (identity-opts))]
      (if response
        response
        {:status 200
         :body {:gameId (:game-id context)
                :selectedSeat (gm-identity/seat-summary (:selected-seat context))
                :seats (mapv gm-identity/seat-summary (:seats context))}})))

  (POST "/api/player-token" [:as request]
    (player-token-response request))

  ;; --- Anonymous visitors ---
  ;;
  ;; Every Referron Market page carrying ?ref= is already an attributable surface: an essay
  ;; is a share target with no affiliate layer bolted on, which is what keeps the
  ;; economics from surfacing as link-spam. The machinery is gm-lib's
  ;; game.gm.visitor — Referron Market is the second consumer, which is what justified
  ;; lifting it out of the-js.
  ;;
  ;; `wrap-cookies` below is load-bearing: without it every visit mints a FRESH
  ;; visitor id and nothing is ever claimable, while every call still returns 200.
  (OPTIONS "/public/touch" []
    {:status 204
     :headers {"Access-Control-Allow-Origin" "*"
               "Access-Control-Allow-Headers" "Content-Type"
               "Access-Control-Allow-Methods" "POST, OPTIONS"}})
  (POST "/public/touch" [:as request]
    (gm-visitor/record-visitor-touch request))
  (POST "/coseller/claim-visitor" [:as request]
    (let [{:keys [context seat response]} (gm-identity/require-selected-seat request (identity-opts))]
      (or response
          (gm-visitor/claim-visitor-touches request (:game-name context) seat
                                            {:submit-ui-event-fn submit-ui-event}))))

  (POST "/api/coseller/activate" [:as request]
    (let [{:keys [context seat response]}
          (gm-identity/require-selected-seat request (identity-opts))]
      (or response (activation-response request context seat))))

  (GET "/api/coseller/program" [:as request]
    (let [{:keys [context seat response]}
          (gm-identity/require-selected-seat request (identity-opts))
          roles (set (map str (or (:player-roles seat)
                                  (some-> (:player-role seat) vector)
                                  [])))]
      (cond
        response response
        (not (contains? roles "coseller"))
        {:status 403 :body {:error {:code "coseller-role-required"
                                    :message "Activate Coselling to view private program terms."}}}
        :else
        (if-let [state (effective-state request (:game-id context) (:game-name context))]
          {:status 200
           :body (coseller-program (:gameState state)
                                   (:player-name seat)
                                   (or (gm-config/resolve-app-base-url)
                                       (gm-identity/request-base-url request)))}
          {:status 502 :body {:error {:code "state-unavailable"}}}))))

  (OPTIONS "/events" []
    {:status 204
     :headers {"Access-Control-Allow-Origin" "*"
               "Access-Control-Allow-Headers" "Content-Type, Authorization, X-Selected-Seat"
               "Access-Control-Allow-Methods" "POST, OPTIONS"}})
  (POST "/events" [:as request]
    (let [{:keys [context seat response]} (gm-identity/require-selected-seat request (identity-opts))]
      (or response
          (if (gm-v2/enabled?)
            ;; Runtime v2: the seat is already verified with MoM above, so the event runs
            ;; locally as that seat (caller protections included), not through MoM.
            (gm-v2/ui-event! {:game-id (:game-id context) :seat seat :body (:body request)})
            (let [resp (submit-ui-event request (:game-name context) seat)]
              {:status (:status resp) :body (:body resp)})))))

  (POST "/v1/gm/handle" [:as request]
    (if-not (mom-webhook-authorized? request)
      {:status 401
       :body {:success false
              :error {:code "untrusted_mom_envelope"
                      :message "The GM webhook requires MoM authentication."}}}
      (let [body (:body request)
            result (dispatch (if (map? body) body {}))]
        {:status (or (:status result) 200)
         :body (dissoc result :status)})))

  (GET "/topics" []
    {:status 200 :body {:topics spec/topics}})

  (GET "/ui/public-state" [:as request]
    (let [gname (current-game-name)
          gid (some-> @gm-runtime/game-id str)]
      (if (str/blank? (str gname))
        {:status 400 :body {:error {:code "missing-game-name"
                                    :message "GAME_NAME not set or registration incomplete"}}}
        (if-let [state (public-state request gid gname)]
          {:status 200 :body state}
          {:status 502 :body {:error {:code "state-unavailable"
                                      :message "Failed to load durable game state from MoM"}}}))))

  ;; First-party editorial assets. Keeping featured images on the Referron Market origin
  ;; avoids coupling permanent essay URLs and social cards to an image host.
  (GET "/favicon.ico" []
    {:status 200 :headers {"Content-Type" "image/x-icon" "Cache-Control" "public, max-age=3600"}
     :body (io/input-stream (io/resource "ui/assets/brand/favicon-v2.ico"))})
  (GET "/site.webmanifest" []
    {:status 200 :headers {"Content-Type" "application/manifest+json"}
     :body (slurp (io/resource "ui/site.webmanifest"))})
  (route/resources "/assets" {:root "ui/assets"})

  ;; --- The site ---
  (GET "/*" [:as request]
    (when (str/ends-with? (:uri request) ".md") (discovery-response request (:uri request) true)))

  (GET "/llms.txt" [:as request] (discovery-response request "/llms.txt" true))
  (GET "/sitemap.xml" [:as request] (discovery-response request "/sitemap.xml" true))
  (GET "/robots.txt" [:as request] (discovery-response request "/robots.txt" true))
  (GET "/index.md" [:as request] (discovery-response request "/index.md" true))
  (GET "/operator/discoverability" [] (private-page "index.html"))
  (GET "/operator/sellers" [] (private-page "index.html"))
  (GET "/join" [] (private-page "index.html"))
  (GET "/earnings" [] (private-page "index.html"))
  (GET "/studio" [] (private-page "index.html"))
  (GET "/market/cart" [] (private-page "index.html"))
  (GET "/market/orders" [] (private-page "index.html"))
  (GET "/" [:as request] (discovery-response request "/" false))
  (GET "/essays" [:as request] (discovery-response request "/essays" false))
  (GET "/manifesto" [:as request] (discovery-response request "/manifesto" false))
  (GET "/coselling" [:as request] (discovery-response request "/coselling" false))
  (GET "/seller-agreement" [:as request] (discovery-response request "/seller-agreement" false))
  (GET "/market" [:as request] (discovery-response request "/market" false))
  (GET "/market/:seller/products/:capability" [:as request] (discovery-response request (:uri request) false))
  (GET "/market/:seller" [:as request] (discovery-response request (:uri request) false))
  (GET "/privacy" [:as request] (discovery-response request "/privacy" false))
  (GET "/terms" [:as request] (discovery-response request "/terms" false))
  (GET "/essays/:slug" [:as request] (discovery-response request (:uri request) false))
  (GET "/guides/:guide" [:as request] (discovery-response request (:uri request) false))
  (GET "/topics/:topic" [] (private-page "index.html"))
  (GET "/@:player-name" [] (private-page "index.html"))


  (route/not-found {:status 404 :body {:error "Not found"}}))

(def app
  (-> gm-routes
      ;; /v2/events, /v2/state, and the 410 on /v1/gm/handle (GM_RUNTIME=v2).
      (gm-v2/wrap-v2)
      (json-middleware/wrap-json-body {:keywords? true})
      ;; Anonymous visitor ids ride in a cookie so a reader's ?ref= history
      ;; survives the full navigation to the auth app, which destroys page state.
      (marketplace/wrap-payment-readiness)
      (marketplace/wrap-stripe-raw-body)
      (cookies/wrap-cookies)
      (json-middleware/wrap-json-response)))
