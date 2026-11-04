(ns referron-market.catalog-test
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [game.gm.identity :as identity]
            [game.protocols.discoverability :as discovery]
            [referron-market.catalog :as catalog]
            [referron-market.discoverability :as adapter]
            [referron-market.handlers :as h]))

(deftest preview-is-public-only-and-never-purchasable
  (let [state {:attention {:feed [] :tasks {:draft {:body "private draft"}}}}
        public (h/public-game-state state)
        caps (get-in public [:market :capabilities])]
    (is (= 9 (count caps)))
    (is (= "Yvatar Store" (get-in public [:storefront :profiles "yvatar" :display-name])))
    (is (every? #(false? (get-in % [:commerce :checkout-enabled])) (vals caps)))
    (is (every? catalog/preview-id? (keys caps)))
    (is (nil? (get-in public [:attention :tasks])))
    (is (= [] (get-in public [:attention :feed])))
    (is (nil? (:market state)))
    (is (= public (h/public-game-state public)))))

(deftest durable-store-and-catalog-win
  (let [cap {:id "actual" :seller "yvatar" :title "Seller listing"}
        state {:market {:capabilities {"actual" cap}}
               :storefront {:profiles {:yvatar {:display-name "Seller-owned"}}}}]
    (is (= state (catalog/with-preview state))))
  (let [state {:market {:categories {"software-id" {:slug "software"}}}
               :storefront {:profiles {:yvatar {:display-name "Custom name"}}}}
        public (h/public-game-state state)]
    (is (= "Custom name" (get-in public [:storefront :profiles "yvatar" :display-name])))
    (is (= ["software-id"] (:category-ids (first (vals (get-in public [:market :capabilities]))))))))

(deftest preview-cart-requests-fail-through-http-and-protocol
  (let [event {:event/type "market/add-to-cart"
               :event/data {:capability-id "referron-preview-yvatar-single" :quantity 1}}]
    (is (= "preview-not-purchasable" (get-in (h/dispatch (assoc event :game-state {})) [:error :code])))
    (with-redefs [identity/require-selected-seat
                  (fn [& _] {:context {:game-name "referron-market"}
                             :seat {:player-name "buyer" :player-roles ["buyer"]}})
                  http/post (fn [& _] (throw (Exception. "Must never forward a preview purchase")))]
      (let [response (h/app {:request-method :post :uri "/events" :headers {} :body event})]
        (is (= 409 (:status response)))
        (is (= "preview-not-purchasable" (get-in (json/parse-string (:body response) true) [:error :code])))))))

(deftest legacy-essay-redirects-preserve-attribution
  (doseq [[old new] [["/essays" "/insights"] ["/essays/useful" "/insights/useful"]
                     ["/essays/old-id" "/insights/old-id"] ["/essays/useful.md" "/insights/useful.md"]]]
    (let [response (h/app {:request-method :get :uri old :query-string "ref=ref-a&campaign=launch" :headers {}})]
      (is (= 301 (:status response)))
      (is (= (str new "?ref=ref-a&campaign=launch") (get-in response [:headers "Location"]))))))

(deftest preview-discovery-and-public-routes-agree
  (let [snapshot (adapter/snapshot {})
        context (:context snapshot)
        resource (discovery/resource context "/market/yvatar/products/referron-preview-yvatar-single")]
    (is (= "Yvatar Store" (:title (discovery/resource context "/market/yvatar"))))
    (is (false? (get-in resource [:offer :available?])))
    (is (re-find #"No insights yet" (:html (discovery/resource context "/insights")))))
  (with-redefs [http/get (fn [& _] {:status 200 :body {:success true :state {}}})]
    (doseq [path ["/insights" "/market/yvatar" "/market/yvatar/products/referron-preview-yvatar-single"]]
      (let [response (h/app {:request-method :get :uri path :headers {}})]
        (is (= 200 (:status response)))
        (is (re-find #"<main" (:body response)))))))
