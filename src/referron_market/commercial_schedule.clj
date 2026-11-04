(ns referron-market.commercial-schedule
  "Private, versioned seller commercial offers and authenticated acceptance."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.time Instant] [java.util UUID] [java.security MessageDigest]))

(def text-fields [:offerings :territories :fees :settlement :holds :reversals :termination-subscriptions :additional-terms])
(def calculation-base "Product subtotal after discounts, excluding separately stated tax and shipping.")
(defn normalize-state [state]
  (reduce (fn [s key]
            (update s key #(into {} (map (fn [[id value]] [(if (keyword? id) (name id) (str id)) value])) (or % {}))))
          (or state {}) [:acceptances :commercial-schedules :commercial-acceptances]))
(defn- field [m key] (or (get m key) (get m (name key))))
(defn- fail [status code message] {:success false :status status :error {:code code :message message}})
(defn- digest [s]
  (apply str (map #(format "%02x" (bit-and 0xff %))
                  (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))))
(defn- escape-html [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))
(defn application [state id]
  (first (for [[player records] (:acceptances (normalize-state (:seller-agreement state)))
               record records :when (= id (:id record))]
           [player record (= id (:id (last records)))])))
(defn current [state id]
  (last (get-in (normalize-state (:seller-agreement state)) [:commercial-schedules id :revisions])))
(defn receipt [state schedule]
  (get-in (normalize-state (:seller-agreement state)) [:commercial-acceptances (:id schedule)]))
(defn activation-ready? [state id]
  (let [schedule (current state id) accepted (receipt state schedule)]
    (and schedule accepted (= (:sha256 schedule) (:sha256 accepted)))))
(defn document [application terms version]
  (str "<article class=\"seller-terms commercial-document\"><h2>Commercial Schedule</h2>"
       "<p>Referron and <strong>" (escape-html (:seller-legal-name application)) "</strong></p>"
       "<p>Seller Agreement acceptance: " (escape-html (:id application)) "; schedule revision " version ".</p>"
       "<h3>Commission: " (/ (:commission-bps terms) 100.0) "%</h3>"
       "<p>" calculation-base " This is the seller's total network commission contribution. Referron Market allocates it within its network, including when no coseller is attributable.</p>"
       "<p>Currency: " (escape-html (:currency terms)) ". Payment route: "
       (if (= "seller-direct" (:payment-route terms)) "Seller-direct" "Platform-collected") ".</p>"
       (apply str (for [[key title] [[:offerings "Offerings and billing"] [:territories "Territories"]
                                    [:fees "Fees and responsibilities"] [:settlement "Settlement"]
                                    [:holds "Holds"] [:reversals "Returns and disputes"]
                                    [:termination-subscriptions "Subscriptions after termination"]
                                    [:additional-terms "Additional terms"]]]
                    (str "<h3>" title "</h3><p style=\"white-space:pre-line\">" (escape-html (get terms key)) "</p>")))
       "<p>This schedule supplements the accepted Seller Agreement. It applies prospectively after acceptance and Referron Market activation; it does not change completed orders or create an immediate entitlement to sell.</p></article>"))
(defn- suggested-terms [_] nil)
(defn operator-summary [state application]
  (let [entry (get-in (normalize-state (:seller-agreement state)) [:commercial-schedules (:id application)])
        schedule (current state (:id application))
        suggestion (when-not (:draft entry) (when-not schedule (suggested-terms application)))
        draft (or (:draft entry) (when suggestion {:terms suggestion :suggested true}))]
    {:draft (when draft (assoc draft :html (document application (:terms draft) "draft")))
     :schedule schedule :acceptance (receipt state schedule)
     :history (mapv #(assoc % :acceptance (receipt state %)) (:revisions entry))}))
(defn seller-summary [state player]
  (let [agreement (normalize-state (:seller-agreement state))
        application (last (get-in agreement [:acceptances player]))
        schedule (when application (current state (:id application)))]
    {:schedule schedule :acceptance (receipt state schedule)}))
(defn- operator-context [payload]
  (let [caller (:caller payload)
        id (field (:event/data payload) :acceptance-id)
        [_ record latest?] (application (:game-state payload) id)]
    (cond
      (or (str/blank? (:player-name caller)) (str/blank? (:membership-id caller))) (fail 401 "caller-required" "Choose an authenticated operator seat.")
      (not (some #{"operator" :operator} (:roles caller))) (fail 403 "operator-required" "An Referron Market operator seat is required.")
      (nil? record) (fail 404 "application-not-found" "Seller application not found.")
      (not latest?) (fail 409 "superseded-application" "Use the latest seller application.")
      (not (contains? #{"pending-operator-activation" "approved"} (:status record))) (fail 409 "invalid-status-transition" "Terms can be prepared only before activation for an eligible application.")
      :else {:application record :id id})))
(defn handle-draft [payload]
  (let [{:keys [application id] :as context} (operator-context payload)
        raw (field (:event/data payload) :terms)
        terms (merge (into {} (map (fn [key] [key (str/trim (str (or (field raw key) "")))]) text-fields))
                     {:commission-bps (field raw :commission-bps) :currency (field raw :currency) :payment-route (field raw :payment-route)})
        agreement (normalize-state (:seller-agreement (:game-state payload)))]
    (cond
      (:error context) context
      (not (and (integer? (:commission-bps terms)) (<= 0 (:commission-bps terms) 10000)
                (re-matches #"[A-Z]{3}" (str (:currency terms)))
                (contains? #{"seller-direct" "platform-collected"} (:payment-route terms))
                (every? #(and (seq (get terms %)) (<= (count (get terms %)) 6000)) (butlast text-fields))
                (<= (count (:additional-terms terms)) 6000)))
      (fail 422 "invalid-commercial-terms" "Complete the commercial terms, use an ISO currency and a rate from 0 to 100%.")
      :else
      (let [draft {:id (str (UUID/randomUUID)) :terms terms :saved-at (str (Instant/now)) :operator (:player-name (:caller payload))}
            updated (assoc-in agreement [:commercial-schedules id :draft] draft)]
        {:success true :updated-state {:seller-agreement updated}
         :data {:draft (assoc draft :html (document application terms "draft"))}}))))
(defn handle-publish [payload]
  (let [{:keys [application id] :as context} (operator-context payload)
        data (:event/data payload)
        agreement (normalize-state (:seller-agreement (:game-state payload)))
        entry (get-in agreement [:commercial-schedules id])
        draft (:draft entry)
        existing (last (:revisions entry))]
    (cond
      (:error context) context
      (not= "approved" (:status application)) (fail 409 "approval-required" "Approve the seller application before publishing terms.")
      (not (true? (field data :authorized))) (fail 422 "authority-required" "Confirm authority to offer these terms for Referron Market.")
      (and existing (= (field data :draft-id) (:draft-id existing))) {:success true :data {:schedule existing :already-published true}}
      (or (nil? draft) (not= (:id draft) (field data :draft-id))) (fail 409 "draft-changed" "Save and review the current draft before publishing.")
      :else
      (let [version (inc (count (:revisions entry)))
            html (document application (:terms draft) version)
            schedule {:id (str (UUID/randomUUID)) :draft-id (:id draft) :application-id id
                      :player-name (:player-name application) :membership-id (:membership-id application)
                      :seller-legal-name (:seller-legal-name application) :version version :template-version "1.0"
                      :terms (:terms draft) :html html :sha256 (digest html)
                      :published-at (str (Instant/now)) :operator (:player-name (:caller payload)) :authorized true}
            updated (-> agreement (update-in [:commercial-schedules id :revisions] (fnil conj []) schedule)
                        (update-in [:commercial-schedules id] dissoc :draft))]
        {:success true :updated-state {:seller-agreement updated} :data {:schedule schedule}}))))
(defn handle-accept [payload]
  (let [caller (:caller payload) data (:event/data payload)
        agreement (normalize-state (:seller-agreement (:game-state payload)))
        application (last (get-in agreement [:acceptances (:player-name caller)]))
        schedule (when application (current (:game-state payload) (:id application)))
        prior (receipt (:game-state payload) schedule)
        signer (str/trim (str (or (field data :signer-name) "")))
        title (str/trim (str (or (field data :signer-title) "")))]
    (cond
      (or (str/blank? (:player-name caller)) (str/blank? (:membership-id caller))) (fail 401 "caller-required" "Choose your authenticated seller seat.")
      (or (nil? schedule) (not= (:membership-id caller) (:membership-id schedule))) (fail 404 "schedule-not-found" "No commercial offer exists for this membership.")
      (or (not= (:id schedule) (field data :schedule-id)) (not= (:sha256 schedule) (field data :sha256))) (fail 409 "schedule-changed" "Review the latest commercial terms before accepting.")
      (not (and (true? (field data :accepted)) (true? (field data :authorized)))) (fail 422 "explicit-acceptance-required" "Confirm your authority and acceptance of these terms.")
      (or (str/blank? signer) (> (count signer) 200) (str/blank? title) (> (count title) 120)) (fail 422 "signer-required" "Enter your name and authority.")
      prior {:success true :data {:acceptance prior :already-accepted true}}
      (not= "approved" (:status application)) (fail 409 "approval-required" "The seller application must be approved.")
      :else
      (let [record {:id (str (UUID/randomUUID)) :schedule-id (:id schedule) :application-id (:id application)
                    :version (:version schedule) :sha256 (:sha256 schedule) :seller-legal-name (:seller-legal-name schedule)
                    :player-name (:player-name caller) :membership-id (:membership-id caller)
                    :signer-name signer :signer-title title :authorized true :accepted true :accepted-at (str (Instant/now))}]
        {:success true :updated-state {:seller-agreement (assoc-in agreement [:commercial-acceptances (:id schedule)] record)}
         :data {:acceptance record}}))))
