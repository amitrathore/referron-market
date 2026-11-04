(ns referron-market.storefront
  "Public seller presentation, owned by the authenticated seller seat."
  (:require [clojure.string :as str])
  (:import [java.net URI]))

(def color-fields [:brand-primary :brand-secondary :brand-button])
(def public-fields (into [:display-name :tagline :description :logo-url :website-url :support-email] color-fields))
(def defaults {})
(def upcoming-stores {})

(defn- normalized-profiles [state]
  ;; JSON decoding keywordizes seller IDs as well as field names. Canonicalize
  ;; those IDs before merging defaults or reading the authenticated seller.
  (let [profiles (get-in state [:storefront :profiles] {})]
    (reduce-kv (fn [result seller profile]
                 (if (keyword? seller) (assoc result (name seller) profile) result))
               (into {} (filter (fn [[seller _]] (string? seller))) profiles)
               profiles)))

(defn public-profiles [state]
  (into {} (map (fn [[seller profile]]
                 [seller (select-keys profile public-fields)]))
        (merge upcoming-stores
               (select-keys defaults (map :seller (vals (get-in state [:market :capabilities]))))
               (normalized-profiles state))))

(defn- https-url? [value]
  (or (str/blank? value)
      (try (let [u (URI. value)]
             (and (= "https" (.getScheme u)) (seq (.getHost u)) (nil? (.getUserInfo u))))
           (catch Exception _ false))))

(defn handle-update [payload]
  (let [caller (:caller payload)
        seller (:player-name caller)
        existing (get (normalized-profiles (:game-state payload)) seller)
        data (merge (select-keys existing color-fields)
                    (into {} (map (fn [[key value]] [(if (string? key) (keyword key) key) value])) (:event/data payload)))
        profile (into {} (map (fn [key] [key (str/trim (str (or (get data key) (get data (name key)) "")))])) public-fields)
        fail (fn [status code message] {:success false :status status :error {:code code :message message}})]
    (cond
      (or (str/blank? seller) (str/blank? (:membership-id caller)))
      (fail 401 "caller-required" "Choose an authenticated seller seat.")
      (not (some #{"seller" :seller} (:roles caller)))
      (fail 403 "seller-required" "An activated Seller role is required.")
      (or (str/blank? (:display-name profile))
          (some (fn [[key limit]] (> (count (get profile key)) limit))
                {:display-name 100 :tagline 160 :description 2000
                 :logo-url 2048 :website-url 2048 :support-email 254})
          (not (https-url? (:logo-url profile)))
          (not (https-url? (:website-url profile)))
          (some (fn [key] (let [value (get profile key)]
                            (and (seq value) (not (re-matches #"#[0-9a-fA-F]{6}" value))))) color-fields)
          (and (seq (:support-email profile))
               (not (re-matches #"^[^\s@]+@[^\s@]+\.[^\s@]+$" (:support-email profile)))))
      (fail 422 "invalid-storefront" "Enter a name, valid HTTPS links, a valid support email, and brand colors as #RRGGBB within the field limits.")
      :else
      {:success true
       :updated-state {:storefront (assoc-in (assoc (or (get-in payload [:game-state :storefront]) {})
                                                  :profiles (normalized-profiles (:game-state payload)))
                                            [:profiles seller] profile)}
       :data {:seller seller :profile profile}})))

(def manifest
  {:storefront {:update-profile
                {:handler handle-update :type :command
                 :doc "Publish the authenticated seller's public storefront profile."
                 :summary "Update your storefront." :when-to-use "To introduce your store."
                 :roles #{:seller} :authorization {:caller :member}
                 :input [:map] :output [:map] :errors []}}})
