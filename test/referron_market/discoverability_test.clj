(ns referron-market.discoverability-test
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [game.gm.identity :as identity]
            [game.protocols.discoverability :as d]
            [referron-market.discoverability :as a]
            [referron-market.handlers :as h])
  (:import [org.jsoup Jsoup]))

(def sample
  {:attention {:feed [{:id "e1" :slug "useful" :author "amit" :published-at 1791990000000
                      :payload {:title "Useful tools" :summary "Build something useful"
                                :body "<h2>A practical start</h2><p>Real public content.</p><script>bad()</script>"}}]
               :tasks {:private "secret draft"}}
   :market {:capabilities {"one" {:id "one" :seller "seller" :status "active" :title "Tool"
                                  :description "Useful tool" :terms {:price {:amount 1200 :currency "USD"}}
                                  :commerce {:checkout-enabled false}}}
            :orders {"private" "secret order"}}
   :seller-agreement {:acceptances {:private "secret agreement"}}
   :coseller {:signups {:private "secret network"}}})

(deftest adapter-excludes-private-state-and-unavailable-offers
  (let [s (a/snapshot sample) context (:context s) p (d/profile context (:state s))
        r (d/resource context "/market/seller/products/one")]
    (is (not (re-find #"secret draft|secret order|secret agreement|secret network" (pr-str s))))
    (is (nil? (get-in (d/structured-data p r) ["@graph" 2 "offers"])))
    (is (re-find #"Coming soon" (:html r)))
    (is (= "2026" (subs (:published-at (d/resource context "/essays/useful")) 0 4)))
    (is (seq (:html (d/resource context "/privacy"))))
    (is (re-find #"Referron" (:html (d/resource context "/terms"))))))

(deftest public-http-renders-content-and-discovery-before-javascript
  (with-redefs [http/get (fn [& _] {:status 200 :body {:success true :state sample}})]
    (doseq [path ["/" "/essays" "/market" "/coselling" "/seller-agreement" "/privacy" "/terms"
                  "/essays/useful" "/market/seller" "/market/seller/products/one" "/guides/agents"]]
      (let [r (h/app {:request-method :get :uri path :headers {}})
            doc (Jsoup/parse (:body r))]
        (is (= 200 (:status r)) path)
        (is (seq (.text (.select doc "main"))) path)
        (is (= 1 (.size (.select doc "main"))) path)
        (is (= 1 (.size (.select doc "link[rel=canonical]"))) path)
        (is (seq (.text (.select doc "title"))) path)
        (let [data (.html (.first (.select doc "script[type=application/ld+json]")))]
          (is (map? (json/parse-string data)) path))))
    (let [r (h/app {:request-method :get :uri "/essays/e1" :query-string "ref=ref-a" :headers {}})]
      (is (= 200 (:status r)))
      (is (re-find #"rel=\"canonical\" href=\"https://referron.coselling.ai/essays/useful\"" (:body r)))
      (is (not (re-find #"bad\(\)|secret draft|secret order|secret agreement|secret network" (:body r)))))
    (doseq [path ["/llms.txt" "/sitemap.xml" "/robots.txt" "/guides/agents.md" "/essays/useful.md"]]
      (is (= 200 (:status (h/app {:request-method :get :uri path :headers {}}))) path))
    (doseq [path ["/essays/missing" "/market/missing" "/market/seller/products/missing" "/guides/missing.md"]]
      (is (= 404 (:status (h/app {:request-method :get :uri path :headers {}}))) path))
    (is (= "noindex, nofollow" (get-in (h/app {:request-method :get :uri "/studio" :headers {}}) [:headers "X-Robots-Tag"])))))

(deftest missing-state-does-not-serve-stale-content
  (with-redefs [http/get (fn [& _] {:status 503 :body {:error "offline"}})]
    (doseq [path ["/llms.txt" "/sitemap.xml" "/essays/useful" "/market/seller/products/one"]]
      (is (= 503 (:status (h/app {:request-method :get :uri path :headers {}}))) path))))

(deftest registration-sync-forwards-human-authority-and-preserves-protection
  (let [existing {:game/id "g" :game/host-id "host" :game/name "Referron Market" :game/game-name "referron-market"
                  :game/protected-protocols ["existing-private"]}
        posted (atom nil)
        guard (fn [& _] {:context {:game-name "referron-market"} :seat {:player-name "amit" :player-roles ["operator"]}})]
    (with-redefs [identity/require-selected-seat guard
                  identity/request-user-auth (fn [_ options] (assoc options :headers {"authorization" "Bearer existing-human-session"}))
                  http/get (fn [_ options]
                             (is (= "Bearer existing-human-session" (get-in options [:headers "authorization"])))
                             {:status 200 :body [existing]})
                  http/post (fn [_ options]
                              (reset! posted options)
                              (let [payload (json/parse-string (:body options) true)]
                                {:status 201 :body (assoc existing :game/protocols (:protocols payload)
                                                        :game/protected-protocols (:protected-protocols payload))}))]
      (is (= 200 (:status (h/app {:request-method :post :uri "/api/operator/discoverability/sync-registration" :headers {}}))))
      (is (= "Bearer existing-human-session" (get-in @posted [:headers "authorization"])))
      (let [payload (json/parse-string (:body @posted) true)]
        (is (= "host" (:host-id payload)))
        (is (some #{"existing-private"} (:protected-protocols payload)))
        (is (some #{"discoverability"} (:protected-protocols payload)))
        (is (not (contains? payload :initial-state)))
        (is (not (contains? payload :player-slots)))))
    (with-redefs [identity/require-selected-seat guard
                  http/get (fn [& _] {:status 200 :body [existing]})
                  http/post (fn [& _] {:status 403 :body {:error "forbidden"}})]
      (is (= 403 (:status (h/app {:request-method :post :uri "/api/operator/discoverability/sync-registration" :headers {}})))))
    (with-redefs [identity/require-selected-seat (fn [& _] {:seat {:player-roles ["audience"]}})
                  http/post (fn [& _] (throw (ex-info "Must not submit" {})))]
      (is (= 403 (:status (h/app {:request-method :post :uri "/api/operator/discoverability/sync-registration" :headers {}})))))))

(deftest operator-http-flow-to-protocol-and-public-artifact
  (let [store (atom sample)
        caller {:player-name "amit" :membership-id "membership" :roles ["operator"]}
        operator (fn [& _] {:context {:game-name "referron-market"}
                            :seat {:player-name "amit" :player-roles ["operator"]}})]
    (with-redefs [identity/require-selected-seat operator
                  http/get (fn [& _] {:status 200 :body {:success true :state @store}})
                  http/post (fn [_ req]
                              (let [event (json/parse-string (:body req) true)
                                    result (h/dispatch (assoc event :caller caller :game-state @store))]
                                (when (:success result) (swap! store merge (:updated-state result)))
                                {:status 200 :body {:gm-result result}}))]
      (let [post (fn [operation data] (h/app {:request-method :post :uri (str "/api/operator/discoverability/" operation)
                                            :headers {} :body data}))
            submitted (post "submit-narrative" {:name "Referron Market" :summary "Useful tools" :text "An exciting private draft"})
            body (json/parse-string (:body submitted) true)
            revision (get-in body [:data :draft :id])]
        (is (= 200 (:status submitted)))
        (is (not (re-find #"An exciting private draft" (:body (h/app {:request-method :get :uri "/llms.txt" :headers {}})))))
        (is (= 409 (:status (post "review-narrative" {:revision "stale" :decision "approve"}))))
        (is (= 200 (:status (post "review-narrative" {:revision revision :decision "approve"}))))
        (is (re-find #"An exciting private draft" (:body (h/app {:request-method :get :uri "/llms.txt" :headers {}}))))
        (is (= 200 (:status (h/app {:request-method :get :uri "/api/operator/discoverability" :headers {}}))))))
    (with-redefs [identity/require-selected-seat (fn [& _] {:seat {:player-name "reader" :player-roles ["audience"]}})]
      (is (= 403 (:status (h/app {:request-method :get :uri "/api/operator/discoverability" :headers {}})))))))

(deftest social-metadata-works-without-javascript-and-keeps-custom-images
  (with-redefs [http/get (fn [& _] {:status 200 :body {:success true :state sample}})]
    (let [response (h/app {:request-method :get :uri "/" :headers {}})
          doc (Jsoup/parse (:body response))
          content (fn [selector] (.attr (.first (.select doc selector)) "content"))]
      (is (= "https://referron.coselling.ai/assets/brand/social-card-v2.png" (content "meta[property=og:image]"))))
    (let [doc (Jsoup/parse (:body (h/app {:request-method :get :uri "/" :headers {}})))]
      (is (= "1200" (.attr (.first (.select doc "meta[property=og:image:width]")) "content")))
      (is (= "630" (.attr (.first (.select doc "meta[property=og:image:height]")) "content")))
      (is (= "summary_large_image" (.attr (.first (.select doc "meta[name=twitter:card]")) "content")))
      (is (re-find #"Make trusted recommendations" (.attr (.first (.select doc "meta[name=description]")) "content")))))
  (let [profile {:origin "https://referron.coselling.ai" :name "Referron Market" :image "/assets/brand/social-card-v2.png"}
        metadata (a/social-metadata profile {:path "/essays/custom" :type "article" :title "Custom" :description "A specific article" :image "https://images.example.com/cover.jpg"})]
    (is (re-find #"https://images.example.com/cover.jpg" metadata))
    (is (not (re-find #"og:image:width" metadata)))))
