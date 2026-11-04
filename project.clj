(defproject referron-market "0.1.1-SNAPSHOT"
  :description "Referron Market — a marketplace built on trusted connections"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  ;; gm-lib comes from Clojars. Never `lein install` a local copy: it shadows the
  ;; published artifact and makes the build green on exactly one machine.
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [ai.intergraph/game-master "0.1.34"]
                 [org.commonmark/commonmark "0.24.0"]
                 [clj-http "3.12.3"]
                 [cheshire "5.11.0"]
                 [compojure "1.7.0"]
                 [ring/ring-json "0.5.1"]
                 [ring/ring-defaults "0.3.4"]
                 [ring/ring-jetty-adapter "1.9.6"]
                 ;; Runtime v2 (GM_RUNTIME=v2): the GM's own Postgres store. They are
                 ;; :provided in gm-lib on purpose, so a GM that wants v2 adds them.
                 [com.github.seancorfield/next.jdbc "1.3.939"]
                 [org.postgresql/postgresql "42.7.4"]
                 [com.zaxxer/HikariCP "6.2.1"]]
  :main ^:skip-aot referron-market.core
  :uberjar-name "referron-market-standalone.jar"
  :target-path "target/%s"
  :profiles {:verify {:source-paths ["test"]}
             :dev {:dependencies [[buddy/buddy-sign "3.5.351"]]}
             :uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
