(ns referron-market.storefront-test
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [referron-market.storefront :as storefront]
            [referron-market.handlers :as handlers]))

(def payload
  {:caller {:player-name "builder" :membership-id "member-1" :roles ["seller"]}
   :game-state {:storefront {:profiles {"other" {:display-name "Other"}}}}
   :event/data {:seller "other" :display-name "Builder tools" :tagline "Tools for people"
                :description "Useful tools." :logo-url "https://example.com/logo.png"
                :website-url "https://example.com" :support-email "support@example.com"}})

(deftest seller-owns-the-profile-and-private-fields-are-excluded
  (let [result (storefront/handle-update payload)
        state (:updated-state result)]
    (is (:success result))
    (is (= "Builder tools" (get-in state [:storefront :profiles "builder" :display-name])))
    (is (= "Other" (get-in state [:storefront :profiles "other" :display-name])))
    (is (nil? (get-in state [:storefront :profiles "builder" :seller])))
    (is (nil? (get-in (handlers/public-game-state
                       {:storefront {:profiles {"builder" {:display-name "Builder" :signer-name "Private"}}}})
                      [:storefront :profiles "builder" :signer-name])))))

(deftest profile-requires-an-activated-seller-and-safe-inputs
  (is (= 401 (:status (storefront/handle-update (dissoc payload :caller)))))
  (is (= 403 (:status (storefront/handle-update (assoc-in payload [:caller :roles] ["audience"])))))
  (doseq [[key value] [[:logo-url "javascript:alert(1)"] [:website-url "http://example.com"]
                     [:support-email "invalid"] [:display-name ""] [:description (apply str (repeat 2001 "a"))]
                     [:brand-primary "red"] [:brand-secondary "#fff"] [:brand-button "#ffffff;display:none"]]]
    (is (= 422 (:status (storefront/handle-update (assoc-in payload [:event/data key] value)))))))

(deftest no-seeded-sellers
  (is (= {} (storefront/public-profiles {}))))

(deftest brand-colors-persist-and-legacy-edits-preserve-them
  (let [colors {:brand-primary "#123456" :brand-secondary "#abcdef" :brand-button "#000000"}
        first-save (storefront/handle-update (update payload :event/data merge colors))
        profile (get-in first-save [:data :profile])
        legacy-save (storefront/handle-update (assoc payload :game-state (:updated-state first-save)))
        cleared (storefront/handle-update (-> payload
                                             (assoc :game-state (:updated-state first-save))
                                             (update :event/data merge (zipmap storefront/color-fields (repeat "")))))]
    (is (:success first-save))
    (is (= colors (select-keys profile storefront/color-fields)))
    (is (= colors (select-keys (get-in legacy-save [:data :profile]) storefront/color-fields)))
    (is (= colors (select-keys (get (storefront/public-profiles (:updated-state first-save)) "builder") storefront/color-fields)))
    (is (every? empty? (map (get-in cleared [:data :profile]) storefront/color-fields)))))

(deftest no-inherited-seller-colors
  (is (= {} (select-keys (get (storefront/public-profiles {:storefront {:profiles {"yvatar" {:display-name "Custom"}}}}) "yvatar") storefront/color-fields))))

(deftest json-reloaded-profiles-retain-colors-and-seller-identity
  (let [state (json/parse-string (json/generate-string
                                  {:storefront {:profiles {"yvatar" {:display-name "Yvatar"}
                                                          "builder" {:display-name "Builder" :brand-primary "#112233"}}}}) true)
        profiles (storefront/public-profiles state)
        result (storefront/handle-update (assoc payload :game-state state))]
    (is (= {} (select-keys (get profiles "yvatar") storefront/color-fields)))
    (is (= "#112233" (get-in profiles ["builder" :brand-primary])))
    (is (= "#112233" (get-in result [:data :profile :brand-primary])))
    (is (= 1 (count (filter #{"builder"} (keys (get-in result [:updated-state :storefront :profiles]))))))
    (is (nil? (get-in result [:updated-state :storefront :profiles :builder])))))
