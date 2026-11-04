(require '[cheshire.core :as json] '[referron-market.spec :as spec])
(println (json/generate-string
  (assoc spec/game-spec :game-name (or (System/getenv "GAME_NAME") "referron-market")
                        :host-id (System/getenv "GM_OWNER_ID"))))
