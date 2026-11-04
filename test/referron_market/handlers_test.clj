(ns referron-market.handlers-test
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [game.gm.identity :as gm-identity]
            [referron-market.handlers :as handlers]))

(def sample-state
  {:attention {:brand-bible {:name "Referron Market"}
               :feed [{:id "essay-1" :payload {:title "Useful"}}]
               :tasks {:secret {:draft "not public"}}
               :audit-log [{:action :edited}]}
   :market {:capabilities {"yvatar-1" {:id "yvatar-1"
                                       :seller "yvatar"
                                       :title "Yvatar credits"
                                       :coseller-commission-bps 3500
                                       :history [{:updates {:coseller-commission-bps 3500}}]
                                       :status :active
                                       :commerce {:adapter "yvatar"}}
                          "liam-1" {:id "liam-1"
                                    :seller "liam-seller"
                                    :title "Liam Plus"
                                    :status :active
                                    :commerce {:adapter "liam"}}}
            :orders {"private" {:buyer "someone"}}
            :carts {"private" {}}
            :audit-log [{:action :paid}]}
   :coseller {:cosellers {"ari" {:ref-token "ref-ari"}}
              :signups {"ari" {:referred-by "amit"}}
              :touches [{:buyer "buyer" :coseller "ari"}]
              :policy {:algorithm "30-days-linear"
                       :pool-share-bps 8000
                       :window-days 180
                       :payout-hold-days 3
                       :seller-direct-payout-hold-days 1
                       :referral-override-bps 500}
              :settlements {"private" {}}
              :payouts {"private" {}}}
   :gm {:progress-token "secret"}})

(deftest essays-list-is-a-cold-loadable-page
  (with-redefs [http/get (fn [& _] {:status 200 :body {:success true :state sample-state}})]
    (is (= 200 (:status (handlers/app {:request-method :get
                                    :uri "/insights"
                                    :headers {}}))))))

(deftest agent-guide-is-public-plain-text
  (with-redefs [http/get (fn [& _] {:status 200 :body {:success true :state sample-state}})]
   (let [response (handlers/app {:request-method :get :uri "/llms.txt" :headers {}})
        body (:body response)
        agents (:body (handlers/app {:request-method :get :uri "/guides/agents.md" :headers {}}))]
    (is (= 200 (:status response)))
    (is (= "text/plain; charset=utf-8" (get-in response [:headers "Content-Type"])))
    (is (.startsWith body "# Referron Market"))
    (is (.contains body "/guides/agents.md"))
    (is (.contains agents "https://mcp.intergraph.ai/mcp"))
    (is (.contains agents "introspection/query-capabilities")))))

(deftest operator-review-route-rejects-a-normal-player
  (with-redefs [gm-identity/require-selected-seat
                (fn [_ _] {:context {:game-name "referron-market"}
                           :seat {:player-name "reader" :player-roles ["audience"]}})
                http/get (fn [& _] (throw (ex-info "Private state must not be fetched" {})))
                http/post (fn [& _] (throw (ex-info "Review must not be submitted" {})))]
    (is (= 403 (:status (handlers/app {:request-method :get
                                       :uri "/api/operator/sellers" :headers {}}))))
    (is (= 403 (:status (handlers/app {:request-method :post
                                       :uri "/api/operator/sellers/review" :headers {}}))))
    (doseq [uri ["/api/operator/sellers/commercial-schedule" "/api/operator/sellers/commercial-schedule/publish"]]
      (is (= 403 (:status (handlers/app {:request-method :post :uri uri :headers {}})))))))

(deftest anonymous-state-is-a-purpose-built-projection
  (let [public (handlers/public-game-state sample-state)]
    (is (= #{:attention :market :storefront} (set (keys public))))
    (is (= #{:brand-bible :feed} (set (keys (:attention public)))))
    (is (= #{:capabilities} (set (keys (:market public)))))
    (is (nil? (get-in public [:market :capabilities "yvatar-1"
                              :coseller-commission-bps])))
    (is (nil? (get-in public [:market :capabilities "yvatar-1" :history])))
    (is (nil? (:coseller public)))
    (is (nil? (:gm public)))))

(deftest private-coseller-program-carries-economics-and-opportunities
  (let [program (handlers/coseller-program sample-state "ari" "https://referron.test/")]
    (is (= "active" (:status program)))
    (is (= "https://referron.test/?ref=ref-ari" (:share-url program)))
    (is (= 180 (get-in program [:policy :window-days])))
    (is (= 3 (get-in program [:policy :payout-hold-days])))
    (is (= 1 (get-in program [:policy :seller-direct-payout-hold-days])))
    (is (= 500 (get-in program [:policy :referral-override-bps])))
    (is (empty? (:opportunities program)))
    (is (false? (:earnings-enabled program)))
    (testing "an eligible member without registration has no sharing token"
      (let [eligible (handlers/coseller-program sample-state "new-member" "https://referron.test")]
        (is (= "needs-registration" (:status eligible)))
        (is (nil? (:share-url eligible)))))))

(deftest ui-events-forward-the-complete-role-set
  (let [sent (atom nil)]
    (with-redefs [gm-identity/mom-url (constantly "https://mom.test")
                  gm-identity/request-user-auth (fn [_ req] req)
                  http/post (fn [_ req] (reset! sent (json/parse-string (:body req) true))
                              {:status 200 :body {:gm-result {:success true}}})]
      (#'handlers/submit-ui-event
       {:body {:event/type "coseller/register" :event/data {}}}
       "referron-market"
       {:player-name "ari"
        :player-role "audience"
        :player-roles ["audience" "coseller"]})
      (is (= ["audience" "coseller"] (get-in @sent [:event/data :roles])))
      (is (= "ari" (get-in @sent [:event/data :player-name])))
      (is (= "ari" (:caller-player-name @sent))))))

(deftest seat-token-exchange-uses-the-verified-selected-seat
  (let [posted (atom nil)
        request {:headers {"authorization" "Bearer user-token"
                           "x-selected-seat" "yvatar"}}
        guard (fn [_ _] {:context {:game-name "referron-market"}
                         :seat {:player-name "yvatar"}})]
    (with-redefs [gm-identity/require-selected-seat guard
                  gm-identity/mom-url (constantly "https://mom.test")
                  http/post (fn [url options]
                              (reset! posted {:url url :options options})
                              {:status 200 :body {:token "seat-jwt"
                                                  :expires-at 1234567890000
                                                  :expires-in 3600}})]
      (let [response (#'handlers/player-token-response request)]
        (is (= 200 (:status response)))
        (is (= "no-store, max-age=0" (get-in response [:headers "Cache-Control"])))
        (is (= {:token "seat-jwt" :playerName "yvatar"
                :expiresAt 1234567890000 :expiresIn 3600}
               (:body response)))
        (is (= "https://mom.test/auth/player-token" (:url @posted)))
        (is (= "Bearer user-token" (get-in @posted [:options :headers "Authorization"])))
        (is (= {:game-name "referron-market" :caller-player-name "yvatar"}
               (json/parse-string (get-in @posted [:options :body]) true)))))))

(deftest seat-token-exchange-refuses-a-missing-seat
  (with-redefs [gm-identity/require-selected-seat
                (fn [_ _] {:response {:status 400 :body {:error {:code "seat-required"}}}})
                http/post (fn [& _] (throw (ex-info "Should not call MoM" {})))]
    (let [response (#'handlers/player-token-response {:headers {}})]
      (is (= 400 (:status response)))
      (is (= "seat-required" (get-in response [:body :error :code])))
      (is (= "no-store, max-age=0" (get-in response [:headers "Cache-Control"]))))))

(deftest player-token-route-requires-authentication
  (with-redefs [gm-identity/require-selected-seat
                (fn [_ _] {:response {:status 401 :body {:error {:code "unauthorized"}}}})]
    (let [response (handlers/app {:request-method :post
                                  :uri "/api/player-token"
                                  :headers {}})]
      (is (= 401 (:status response)))
      (is (= "no-store, max-age=0" (get-in response [:headers "Cache-Control"]))))))

(deftest a-member-cannot-read-the-private-game-snapshot
  (let [r (handlers/dispatch {:event/type "gm/query-state" :event/data {}
                              :caller {:type "member" :player-name "member" :membership-id "m" :roles ["audience"]}
                              :game-state {:seller-agreement {:private "secret"}}})]
    (is (= 403 (:status r)))
    (is (= "private-state-forbidden" (get-in r [:error :code])))))
