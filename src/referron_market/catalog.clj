(ns referron-market.catalog
  "Reviewed public catalog previews. Never used as protocol or payment state."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def snapshot (json/parse-string (slurp (io/resource "catalog/yvatar.json")) true))

(defn preview-id? [id]
  (and (some? id) (str/starts-with? (str id) "referron-preview-yvatar-")))

(defn preview-event? [event]
  (and (= "market/add-to-cart" (:event/type event))
       (preview-id? (get-in event [:event/data :capability-id]))))

(def preview-error
  {:success false :status 409
   :error {:code "preview-not-purchasable"
           :message "Purchasing on Referron Market is coming soon."}})

(defn with-preview [state]
  (let [caps (get-in state [:market :capabilities] {})
        live? (some #(= "yvatar" (str (:seller %))) (vals caps))
        software-id (some (fn [[id c]] (when (= "software" (:slug c)) (or (:id c) (name id))))
                          (get-in state [:market :categories]))
        profile (or (get-in state [:storefront :profiles "yvatar"])
                    (get-in state [:storefront :profiles :yvatar]))
        previews (into {} (map (fn [[id c]]
                                 [(name id) (-> c
                                                (assoc :category-ids (if software-id [software-id] []))
                                                (assoc-in [:commerce :checkout-enabled] false))]))
                       (:capabilities snapshot))]
    (cond-> state
      (not live?) (assoc-in [:market :capabilities] (merge previews caps))
      (not live?) (update-in [:storefront :profiles] dissoc :yvatar)
      (not live?) (assoc-in [:storefront :profiles "yvatar"]
                            (merge (:profile snapshot) profile)))))
