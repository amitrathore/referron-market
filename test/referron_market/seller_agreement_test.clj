(ns referron-market.seller-agreement-test
  (:require [clojure.test :refer [deftest is testing]]
            [referron-market.seller-agreement :as agreement]
            [referron-market.spec :as spec]
            [referron-market.handlers :as handlers]))

(defn- acceptance-payload [data]
  {:caller {:player-name "seller-applicant"
            :membership-id "member-123"
            :roles ["audience"]}
   :game-state {}
   :event/data (merge {:version (:version (agreement/current-agreement))
                       :sha256 (:sha256 (agreement/current-agreement))
                       :accepted true
                       :seller-type "business"
                       :seller-legal-name "Example Seller Inc"
                       :signer-name "Alex Example"
                       :signer-title "CEO"
                       :notice-email "alex@example.com"
                       :notice-address "1 Market St, Ohio"}
                      data)})

(deftest acceptance-is-private-versioned-and-pending
  (let [terms (agreement/current-agreement)
        result (agreement/handle-accept (acceptance-payload {}))
        record (get-in result [:data :acceptance])]
    (is (seq (:html terms)))
    (is (= 64 (count (:sha256 terms))))
    (is (:success result))
    (is (= "pending-operator-activation" (:status record)))
    (is (= "member-123" (:membership-id record)))
    (is (= (:sha256 terms) (:sha256 record)))
    (is (= record (agreement/latest-acceptance (:updated-state result) "seller-applicant")))
    (is (some #{"seller-agreement"} (:protected-protocols spec/game-spec)))
    (is (some #{"seller-agreement"} (:protocols spec/game-spec)))
    (is (nil? (:seller-agreement (handlers/public-game-state (:updated-state result)))))
    (is (= {:caller :member}
           (get-in handlers/merged-manifest [:seller-agreement :accept :authorization])))))

(deftest acceptance-requires-current-terms-and-signing-authority
  (testing "the exact terms and explicit checkbox are required"
    (is (= "agreement-version-changed"
           (get-in (agreement/handle-accept (acceptance-payload {:sha256 "old"})) [:error :code])))
    (is (= "explicit-acceptance-required"
           (get-in (agreement/handle-accept (acceptance-payload {:accepted false})) [:error :code]))))
  (testing "a business representative must identify their title"
    (is (= "seller-identity-required"
           (get-in (agreement/handle-accept (acceptance-payload {:signer-title ""})) [:error :code]))))
  (testing "the canonical member is required"
    (is (= "authenticated-seat-required"
           (get-in (agreement/handle-accept (assoc (acceptance-payload {}) :caller nil)) [:error :code])))))

(deftest repeat-acceptance-does-not-create-a-second-record
  (let [payload (acceptance-payload {})
        first-result (agreement/handle-accept payload)
        second-result (agreement/handle-accept
                       (assoc payload :game-state (:updated-state first-result)))]
    (is (true? (get-in second-result [:data :already-accepted])))
    (is (= (get-in first-result [:data :acceptance])
           (get-in second-result [:data :acceptance])))
    (is (nil? (:updated-state second-result)))))

(deftest operator-review-records-decisions-and-enforces-transitions
  (let [accepted (agreement/handle-accept (acceptance-payload {}))
        state (:updated-state accepted)
        id (get-in accepted [:data :acceptance :id])
        payload {:caller {:player-name "referron-operator" :membership-id "operator-1"
                          :roles ["operator"]}
                 :game-state state
                 :event/data {:acceptance-id id :decision "approved" :note "Authority verified"}}
        approved (agreement/handle-review payload)
        activated (agreement/handle-review
                   (-> payload
                       (assoc :game-state (:updated-state approved))
                       (assoc-in [:event/data :decision] "activated")))]
    (is (= 403 (:status (agreement/handle-review (assoc payload :caller nil)))))
    (is (= "approved" (get-in approved [:data :acceptance :status])))
    (is (= "referron-operator" (get-in approved [:data :acceptance :reviews 0 :operator])))
    (is (= "commercial-acceptance-required" (get-in activated [:error :code])))
    (is (= 409 (:status (agreement/handle-review
                         (assoc payload :game-state (:updated-state approved))))))
    (is (= 404 (:status (agreement/handle-review
                         (assoc-in payload [:event/data :acceptance-id] "missing")))))
    (is (= 1 (count (agreement/applications (:updated-state approved)))))))
