(ns referron-market.shoptype-test
  (:require [clojure.test :refer :all]
            [referron-market.handlers :as handlers]
            [referron-market.spec :as spec]
            [game.gm.runtime :as runtime]
            [game.gm.shoptype :as gateway]
            [clj-http.client :as http]
            [clojure.string :as str]))

(defn- fly-env
  "The [env] table of fly.toml as a map of plain string values."
  []
  (let [section (second (re-find #"(?s)\[env\]\n(.*?)\n\[" (slurp "fly.toml")))]
    (into {} (for [[_ k v] (re-seq #"(?m)^\s*([A-Z0-9_]+)\s*=\s*'([^']*)'" section)] [k v]))))

(deftest production-connects-shoptype-in-preview-without-enabling-purchasing
  (let [env (fly-env)]
    (is (= "true" (env "SHOPTYPE_ENABLED")))
    (is (= "preview" (env "SHOPTYPE_ENVIRONMENT")))
    (is (= "referron-market" (env "SHOPTYPE_MARKET_ID")))
    (is (= "https://app.shoptype.com" (env "SHOPTYPE_BASE_URL")))
    (is (= "stub" (env "MARKET_PAYMENT_PROVIDER")) "purchasing stays stubbed")
    (is (= "false" (env "STRIPE_CONNECT_ENABLED")) "live payouts stay off")))

(deftest deploy-forwards-the-shoptype-service-credential-and-guards-live-keys
  (let [script (slurp "scripts/deploy.sh")
        allowlist (re-find #"keys=\[[^\]]*\]" script)]
    (is (str/includes? allowlist "'SHOPTYPE_SERVICE_TOKEN'"))
    (is (str/includes? script "sk_live_")
        "a live Stripe key would silently disable preview Shoptype, so deploy refuses it")))

(deftest owner-operations-are-forwarded-to-mom-with-the-callers-own-token
  (let [calls (atom [])]
    (with-redefs [gateway/enabled? (constantly true)
                  gateway/runtime-v2? (constantly false)
                  runtime/game-name-handle (atom "referron-market")
                  http/post (fn [url req] (swap! calls conj [url req])
                              {:status 200 :body {:success true :data {:owner true}}})]
      (let [response (handlers/gm-routes {:uri "/shoptype/v0.1/operations" :request-method :post
                                          :headers {"authorization" "Bearer owner-token"}
                                          :body {:operation "query-context"}})
            [[url req]] @calls]
        (is (= 200 (:status response)))
        (is (= 1 (count @calls)))
        (is (str/ends-with? url "/api/v1/games/referron-market/shoptype/operator"))
        (is (= "Bearer owner-token" (get-in req [:headers "Authorization"])))
        (is (not (str/includes? (:body req) "caller-player-name"))
            "the owner acts as themselves; no player identity is asserted")))))

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
