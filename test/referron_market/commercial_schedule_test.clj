(ns referron-market.commercial-schedule-test
  (:require [clojure.test :refer [deftest is]] [cheshire.core :as json]
            [referron-market.commercial-schedule :as commercial]
            [referron-market.seller-agreement :as agreement]
            [referron-market.handlers :as handlers]))
(def applicant {:player-name "seller" :membership-id "member-1" :roles ["audience"]})
(def operator {:player-name "amit" :membership-id "operator-1" :roles ["operator"]})
(def app {:id "application-1" :player-name "seller" :membership-id "member-1"
          :seller-legal-name "Seller Inc" :status "approved" :accepted-at "2026-10-13T00:00:00Z"})
(def initial {:seller-agreement {:acceptances {"seller" [app]}}})
(def terms (merge (zipmap commercial/text-fields (repeat "Documented agreed terms."))
                  {:commission-bps 4000 :currency "USD" :payment-route "seller-direct"}))
(defn draft [state value]
  (commercial/handle-draft {:caller operator :game-state state :event/data {:acceptance-id (:id app) :terms value}}))
(defn publish [saved]
  (commercial/handle-publish {:caller operator :game-state (:updated-state saved)
                             :event/data {:acceptance-id (:id app) :draft-id (get-in saved [:data :draft :id]) :authorized true}}))
(defn accept [published]
  (let [schedule (get-in published [:data :schedule])]
    (commercial/handle-accept {:caller applicant :game-state (:updated-state published)
                              :event/data {:schedule-id (:id schedule) :sha256 (:sha256 schedule)
                                           :accepted true :authorized true :signer-name "Marc" :signer-title "Representative"}})))

(deftest published-schedule-and-acceptance-are-private-immutable-and-activate
  (let [saved (draft initial terms) published (publish saved) accepted (accept published)
        schedule (get-in published [:data :schedule])
        state (:updated-state accepted)
        payload {:caller operator :game-state state
                    :event/data {:acceptance-id (:id app) :decision "activated" :configuration-confirmed true}}
        result (agreement/handle-review payload)]
    (is (:success saved)) (is (:success published)) (is (:success accepted))
    (is (.contains (:html schedule) "40.0%"))
    (is (= 64 (count (:sha256 schedule))))
    (is (= schedule (commercial/current state (:id app))))
    (is (= "activated" (get-in result [:data :acceptance :status])))
    (is (= (:id schedule) (get-in result [:data :acceptance :reviews 0 :commercial-schedule-id])))
    (is (= "configuration-confirmation-required" (get-in (agreement/handle-review (assoc-in payload [:event/data :configuration-confirmed] false)) [:error :code])))
    (is (nil? (:seller-agreement (handlers/public-game-state state))))
    (is (= {:schedule nil :acceptance nil} (commercial/seller-summary state "other")))
    (is (:success (accept (assoc published :updated-state state))))))

(deftest permissions-completeness-and-explicit-consent-are-required
  (is (= 422 (:status (draft initial (assoc terms :commission-bps 10001)))))
  (is (= 422 (:status (draft initial (assoc terms :fees "")))))
  (is (= 403 (:status (commercial/handle-draft {:caller applicant :game-state initial :event/data {:acceptance-id (:id app) :terms terms}}))))
  (let [saved (draft (assoc-in initial [:seller-agreement :acceptances "seller" 0 :status] "pending-operator-activation") terms)]
    (is (= "approval-required" (get-in (publish saved) [:error :code]))))
  (let [published (publish (draft initial terms)) schedule (get-in published [:data :schedule])
        payload {:caller applicant :game-state (:updated-state published)
                 :event/data {:schedule-id (:id schedule) :sha256 (:sha256 schedule) :signer-name "Signer" :signer-title "CEO" :accepted true :authorized true}}]
    (is (= 404 (:status (commercial/handle-accept (assoc-in payload [:caller :membership-id] "other-member")))))
    (is (= 404 (:status (commercial/handle-accept (assoc-in payload [:caller :player-name] "other")))))
    (is (= 422 (:status (commercial/handle-accept (assoc-in payload [:event/data :authorized] false)))))
    (is (= 409 (:status (commercial/handle-accept (assoc-in payload [:event/data :sha256] "stale")))))))

(deftest revisions-replays-and-json-reloaded-identities
  (let [saved (draft initial terms) published (publish saved) accepted (accept published)
        reloaded (json/parse-string (json/generate-string (:updated-state accepted)) true)
        next-saved (draft reloaded (assoc terms :commission-bps 4500)) next-published (publish next-saved)
        old (get-in published [:data :schedule])]
    (is (commercial/activation-ready? reloaded (:id app)))
    (is (= "Seller Inc" (:seller-legal-name (agreement/latest-acceptance reloaded "seller"))))
    (is (not (commercial/activation-ready? (:updated-state next-published) (:id app))))
    (is (= 2 (get-in next-published [:data :schedule :version])))
    (is (= old (first (get-in (:updated-state next-published) [:seller-agreement :commercial-schedules (:id app) :revisions]))))
    (is (= "schedule-changed" (get-in (accept (assoc published :updated-state (:updated-state next-published))) [:error :code])))
    (is (true? (get-in (commercial/handle-publish {:caller operator :game-state (:updated-state published)
                                                 :event/data {:acceptance-id (:id app) :draft-id (get-in saved [:data :draft :id]) :authorized true}}) [:data :already-published])))
    (is (true? (get-in (accept (assoc published :updated-state reloaded)) [:data :already-accepted])))))

(deftest template-escapes-user-text-and-force-equals-draft-is-operator-only
  (let [html (commercial/document app (assoc terms :fees "<script>bad()</script>") 1)
        marc (assoc app :player-name "marc_forceequals" :seller-legal-name "Force Equals Inc")
        state {:seller-agreement {:acceptances {"marc_forceequals" [marc]}}}]
    (is (not (.contains html "<script>")))
    (is (.contains html "&lt;script&gt;"))
    (is (nil? (get-in (commercial/operator-summary state marc) [:draft :terms :commission-bps])))
    (is (nil? (:schedule (commercial/seller-summary state "marc_forceequals"))))))
