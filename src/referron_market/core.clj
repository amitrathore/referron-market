(ns referron-market.core
  (:require [referron-market.handlers :refer [app dispatch]]
            [referron-market.spec :as spec]
            [game.gm.config :as gm-config]
            [game.gm.runtime :as gm-runtime]
            [game.gm.v2 :as gm-v2])
  (:gen-class))

(defn -main [& args]
  (println "--- Starting Referron Market Game Master ---")
  (let [cfg (gm-config/build-config args)]
    (gm-runtime/start-gm!
      (-> (assoc cfg :game-spec spec/game-spec :agent-name "Referron Market GM")
          ;; Runtime v2 (GM_RUNTIME=v2): once registered and claimed, open the GM's own
          ;; store and start serving /v2/events. A failure here stops the GM.
          (gm-v2/with-runtime {:dispatch-fn dispatch
                               :initial-state (:initial-state spec/game-spec)
                               :protected-protocols (:protected-protocols spec/game-spec)}))
      app)))
