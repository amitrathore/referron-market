(ns referron-market.discoverability-preview
  "Local verification only: real Referron Market HTTP/protocol code, isolated in-memory state.
   No production credentials, network services, or real seller records."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [game.gm.identity :as identity]
            [game.gm.runtime :as runtime]
            [ring.adapter.jetty :as jetty]
            [referron-market.discoverability-test :refer [sample]]
            [referron-market.handlers :as h]))
(defn -main [& [port]]
  (reset! runtime/game-name-handle "referron-market")
  (reset! runtime/game-id "preview-game")
  (let [store (atom sample)
        seat {:player-name "reviewer" :membership-id "preview-membership" :player-roles ["operator"]}
        caller {:player-name "reviewer" :membership-id "preview-membership" :roles ["operator"]}
        signed? #(= "Bearer preview-operator" (get-in % [:headers "authorization"]))
        respond (fn [body] {:status 200 :headers {"Content-Type" "application/json"} :body (json/generate-string body)})
        public-seat {:playerName "reviewer" :playerRoles ["operator"]}]
    (with-redefs [identity/require-selected-seat
                  (fn [request _] (if (signed? request) {:context {:game-name "referron-market"} :seat seat}
                                     {:response {:status 403 :body {:error {:code "operator-required"}}}}))
                  http/get (fn [& _] {:status 200 :body {:success true :state @store}})
                  http/post (fn [_ request]
                              (let [event (json/parse-string (:body request) true)
                                    result (h/dispatch (assoc event :caller caller :game-state @store))]
                                (when (:success result) (swap! store merge (:updated-state result)))
                                {:status 200 :body {:gm-result result}}))]
      (println "Isolated discovery verification at" (str "http://127.0.0.1:" (or port "4188")))
      (jetty/run-jetty
       (fn [request]
         (case (:uri request)
           "/session" (respond {:authenticated (signed? request) :seatCount (if (signed? request) 1 0)
                                :selectedSeat (when (signed? request) public-seat)})
           "/seats" (respond {:seats [public-seat] :selectedSeat public-seat})
           "/auth.json" (respond {})
           "/api/market/purchases" (respond {:checkouts [] :batches [] :orders []})
           "/api/market/subscriptions" (respond {:subscriptions []})
           "/coseller/claim-visitor" (respond {})
           (h/app request)))
       {:host "127.0.0.1" :port (Integer/parseInt (or port "4188")) :join? true}))))
