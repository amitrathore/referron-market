(ns referron-market.seller-agreement
  "Versioned Referron Market Seller terms and durable, authenticated click acceptance."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [referron-market.commercial-schedule :as commercial])
  (:import [java.security MessageDigest]
           [java.time Instant]
           [java.util UUID]))

(def agreement-id "referron-seller-agreement")
(def agreement-version "1.0")
(def ^:private resource-path "ui/assets/legal/seller-agreement-v1.html")

(defn- sha256 [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes ^String value "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn current-agreement []
  (when-let [resource (io/resource resource-path)]
    (let [html (slurp resource)]
      {:agreement-id agreement-id
       :version agreement-version
       :sha256 (sha256 html)
       :html html})))

(defn latest-acceptance [state player-name]
  (last (get-in (commercial/normalize-state (:seller-agreement state)) [:acceptances (str player-name)])))

(defn applications [state]
  (->> (:acceptances (commercial/normalize-state (:seller-agreement state)))
       vals (mapcat identity)
       (sort-by :accepted-at #(compare %2 %1)) vec))

(defn- field [m key]
  (some-> (or (get m key) (get m (name key))) str str/trim))

(defn- error [status code message]
  {:success false :status status :error {:code code :message message}})

(defn handle-accept [payload]
  (let [caller (:caller payload)
        player-name (:player-name caller)
        membership-id (:membership-id caller)
        data (or (:event/data payload) {})
        agreement (current-agreement)
        seller-name (field data :seller-legal-name)
        signer-name (field data :signer-name)
        signer-title (field data :signer-title)
        notice-email (field data :notice-email)
        notice-address (field data :notice-address)
        seller-type (field data :seller-type)
        accepted? (true? (or (:accepted data) (get data "accepted")))
        state (commercial/normalize-state (:seller-agreement (:game-state payload)))
        prior (last (get-in state [:acceptances (str player-name)]))]
    (cond
      (or (str/blank? (or player-name "")) (str/blank? (or membership-id "")))
      (error 401 "authenticated-seat-required" "Choose an authenticated Referron Market handle to accept.")

      (nil? agreement)
      (error 503 "agreement-unavailable" "The current agreement is unavailable.")

      (not accepted?)
      (error 422 "explicit-acceptance-required" "Check the acceptance box and select I Accept.")

      (or (not= (:version agreement) (field data :version))
          (not= (:sha256 agreement) (field data :sha256)))
      (error 409 "agreement-version-changed" "Review the current agreement before accepting.")

      (or (not (contains? #{"individual" "business"} seller-type))
          (str/blank? seller-name) (> (count seller-name) 200)
          (str/blank? signer-name) (> (count signer-name) 200)
          (and (= seller-type "business") (str/blank? signer-title))
          (> (count (or signer-title "")) 120)
          (str/blank? notice-address) (> (count notice-address) 500)
          (str/blank? notice-email) (> (count notice-email) 254)
          (not (re-matches #"^[^\s@]+@[^\s@]+\.[^\s@]+$" (or notice-email ""))))
      (error 422 "seller-identity-required" "Complete the seller and signer details.")

      (and prior
           (= (:version prior) (:version agreement))
           (= (:sha256 prior) (:sha256 agreement))
           (= (:seller-legal-name prior) seller-name)
           (= (:seller-type prior) seller-type)
           (= (:signer-name prior) signer-name)
           (= (:signer-title prior) signer-title)
           (= (:notice-email prior) notice-email)
           (= (:notice-address prior) notice-address))
      {:success true :data {:acceptance prior :already-accepted true}}

      :else
      (let [record {:id (str (UUID/randomUUID))
                    :agreement-id agreement-id
                    :version (:version agreement)
                    :sha256 (:sha256 agreement)
                    :accepted-at (str (Instant/now))
                    :player-name (str player-name)
                    :membership-id (str membership-id)
                    :seller-legal-name seller-name
                    :seller-type seller-type
                    :signer-name signer-name
                    :signer-title signer-title
                    :notice-email notice-email
                    :notice-address notice-address
                    :status "pending-operator-activation"}
            next-state (update-in state [:acceptances (str player-name)]
                                  (fnil conj []) record)]
        {:success true
         :updated-state {:seller-agreement next-state}
         :data {:acceptance record}
         :events [{:type :seller-agreement-accepted
                   :data {:acceptance-id (:id record)
                          :player-name (str player-name)
                          :agreement-version (:version agreement)
                          :agreement-sha256 (:sha256 agreement)}}]}))))

(defn handle-review [payload]
  (let [caller (:caller payload)
        data (or (:event/data payload) {})
        acceptance-id (field data :acceptance-id)
        decision (field data :decision)
        note (field data :note)
        state (commercial/normalize-state (:seller-agreement (:game-state payload)))
        entries (:acceptances state)
        found (first (for [[player records] entries
                           record records
                           :when (= acceptance-id (:id record))]
                       [player record]))]
    (cond
      (not (some #{"operator" :operator} (:roles caller)))
      (error 403 "operator-required" "An Referron Market operator seat is required.")

      (not (contains? #{"approved" "rejected" "activated"} decision))
      (error 422 "invalid-decision" "Choose approved, rejected, or activated.")

      (or (str/blank? acceptance-id) (> (count (or note "")) 1000))
      (error 422 "invalid-review" "An acceptance ID and a note under 1000 characters are required.")

      (nil? found)
      (error 404 "application-not-found" "The seller application was not found.")

      (not= (:id (last (get entries (first found)))) acceptance-id)
      (error 409 "superseded-application" "Review the seller's latest application.")

      (not (or (and (= "pending-operator-activation" (:status (second found)))
                    (contains? #{"approved" "rejected"} decision))
               (and (= "approved" (:status (second found))) (= "activated" decision))))
      (error 409 "invalid-status-transition" "This application cannot move to that status.")

      (and (= "activated" decision)
           (not (commercial/activation-ready? (:game-state payload) acceptance-id)))
      (error 409 "commercial-acceptance-required" "The seller must accept the latest published commercial terms before activation.")

      (and (= "activated" decision) (not (true? (or (:configuration-confirmed data) (get data "configuration-confirmed")))))
      (error 422 "configuration-confirmation-required" "Confirm the payment configuration matches the accepted commission and payment route.")

      :else
      (let [[player record] found
            schedule (when (= "activated" decision) (commercial/current (:game-state payload) acceptance-id))
            review (cond-> {:decision decision :at (str (Instant/now))
                            :operator (:player-name caller) :note (or note "")}
                     schedule (assoc :commercial-schedule-id (:id schedule) :commercial-sha256 (:sha256 schedule)
                                     :configuration-confirmed true))
            updated (-> record (assoc :status decision)
                        (update :reviews (fnil conj []) review))
            next-state (assoc-in state [:acceptances player]
                                 (mapv #(if (= acceptance-id (:id %)) updated %) (get entries player)))]
        {:success true
         :updated-state {:seller-agreement next-state}
         :data {:acceptance updated}
         :events [{:type :seller-application-reviewed
                   :data {:acceptance-id acceptance-id :decision decision
                          :operator (:player-name caller)}}]}))))

(def manifest
  {:seller-agreement
   {:save-commercial-draft {:handler commercial/handle-draft :type :command
                            :doc "Save private commercial terms for an eligible seller application."
                            :roles #{:operator} :authorization {:caller :member} :input [:map] :output [:map] :errors []}
    :publish-commercial-schedule {:handler commercial/handle-publish :type :command
                                  :doc "Publish the exact saved draft as an immutable offer on Referron Market's behalf."
                                  :roles #{:operator} :authorization {:caller :member} :input [:map] :output [:map] :errors []}
    :accept-commercial-schedule {:handler commercial/handle-accept :type :command
                                 :doc "Accept the latest commercial offer as its authenticated seller representative."
                                 :roles #{:any} :authorization {:caller :member} :input [:map] :output [:map] :errors []}
    :accept {:handler handle-accept
             :type :command
             :doc "Accept the exact public Referron Market Seller agreement as an authenticated player."
             :summary "Record seller agreement acceptance for operator review."
             :when-to-use "Before operator seller activation."
             :roles #{:any}
             :authorization {:caller :member}
             :input [:map]
             :output [:map]
             :errors []}
    :review {:handler handle-review
             :type :command
             :doc "Record an Referron Market operator decision on a seller application."
             :summary "Review or mark seller activation."
             :when-to-use "After seller agreement acceptance."
             :roles #{:operator}
             :authorization {:caller :member}
             :input [:map]
             :output [:map]
             :errors []}}})
