(ns referron-market.migrate
  "Cutover CLI for Referron Market (see game.gm.v2.migrate for the commands and the guards):

     lein run -m referron-market.migrate fetch FILE | diff FILE | import FILE --confirm | rollback-write --confirm"
  (:require [game.gm.v2.migrate :as migrate]))

(def run migrate/run)

(defn -main [& args] (apply migrate/-main args))
