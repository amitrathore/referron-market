(ns referron-market.v2-test
  "Referron Market on gm-lib runtime v2 (GM_RUNTIME=v2): the real app and dispatcher, a fake MoM
   (JWKS, seats, player memory), a memory store and seat tokens signed like MoM's. With the
   flag off nothing changes (the rest of the suite covers that)."
  (:require [buddy.sign.jwt :as jwt]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [game.gm.config :as gm-config]
            [game.gm.identity :as gm-identity]
            [game.gm.runtime :as gm-runtime]
            [game.gm.seat-token :as seat-token]
            [game.gm.store :as store]
            [game.gm.store.memory :as memory]
            [game.gm.v2 :as gm-v2]
            [referron-market.handlers :as h]
            [referron-market.marketplace :as marketplace]
            [referron-market.spec :as spec])
  (:import [java.math BigInteger]
           [java.security KeyPairGenerator]
           [java.util Base64]))

(def game-id "11111111-1111-4111-8111-111111111111")
(def game-name "referron-market")
(def membership-id "22222222-2222-4222-8222-222222222222")

;; --- a fake MoM and seat tokens signed like MoM's ---

(def kp (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048))))

(defn- b64url [^BigInteger n]
  (let [bs (.toByteArray n)
        bs (if (and (> (alength bs) 1) (zero? (aget bs 0))) (java.util.Arrays/copyOfRange bs 1 (alength bs)) bs)]
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs)))

(def jwk
  (let [pub (.getPublic kp)]
    {"kty" "RSA" "use" "sig" "alg" "RS256" "kid" "k1"
     "n" (b64url (.getModulus pub)) "e" (b64url (.getPublicExponent pub))}))

(defn- token []
  (jwt/sign {:membership/id membership-id :game/id game-id :player/name "alex"
             :exp (+ (quot (System/currentTimeMillis) 1000) 3600)}
            (.getPrivate kp) {:alg :rs256 :header {:kid "k1"}}))

(def envelope {:game-id game-id :membership-id membership-id :player-name "alex" :roles ["audience"]
               :oidc-sub "oidc|alex" :joined-at 1700000000000})

(defn- mom-get [url _]
  (cond
    (str/ends-with? url "/.well-known/jwks.json")
    {:status 200 :headers {"Cache-Control" "public, max-age=300"} :body (json/generate-string {"keys" [jwk]})}
    (str/includes? url "/seats/") {:status 200 :body (json/generate-string envelope)}
    (str/includes? url "/player/") {:status 200 :body {:success true :memory {}}}
    :else (throw (ex-info "the GM must not read MoM for its own state on v2" {:url url}))))

(def ^:private mom-posts (atom []))

(defmacro ^:private on-v2 [& body]
  `(let [s# (memory/create-store)]
     (reset! mom-posts [])
     (seat-token/reset-caches!)
     (reset! gm-runtime/game-id game-id)
     (reset! gm-runtime/game-name-handle game-name)
     (try
       (with-redefs [gm-v2/enabled? (constantly true)
                     gm-config/mom-auth-headers (constantly {"Authorization" "Bearer svc"})
                     http/get mom-get
                     http/post (fn [url# o#] (swap! mom-posts conj url#) {:status 200 :body {}})]
         (gm-v2/init! {:gm-id "gm-1" :game-id game-id :game-name game-name}
                      {:dispatch-fn h/dispatch
                       :initial-state (:initial-state spec/game-spec)
                       :protected-protocols (:protected-protocols spec/game-spec)
                       :store s# :mom-url "http://mom.test"})
         ~@body)
       (finally
         (when-let [stop!# (:stop! @gm-v2/runtime)] (stop!#))
         (reset! gm-v2/runtime nil)))))

(defn- request [method uri & [{:keys [body headers]}]]
  (cond-> {:request-method method :uri uri :headers (or headers {})}
    body (-> (assoc :body (java.io.ByteArrayInputStream. (.getBytes ^String (json/generate-string body) "UTF-8")))
             (assoc-in [:headers "content-type"] "application/json"))))

(defn- call [req]
  (let [resp (h/app req)]
    (cond-> resp
      (string? (:body resp)) (update :body #(try (json/parse-string % true) (catch Exception _ %))))))

(defn- authed [] {"authorization" (str "Bearer " (token))})

;; --- the routes ---

(deftest v2-events-runs-an-referron-event-for-a-seat
  (on-v2
   (let [r (call (request :post "/v2/events" {:body {:event/type "market/query-capabilities" :event/data {:player-name "alex"}}
                                              :headers (authed)}))]
     (is (= 200 (:status r)) (pr-str (:body r)))
     (is (true? (:success (:body r)))))
   (testing "no token: the runtime answers, so the request reached it"
     (is (= 401 (:status (call (request :post "/v2/events" {:body {:event/type "market/query-capabilities"}}))))))
   (is (empty? @mom-posts) "nothing went through MoM")))

(deftest v2-state-hides-the-protected-protocols
  (on-v2
   (let [r (call (request :get "/v2/state" {:headers (authed)}))
         state (get-in r [:body :state])]
     (is (= 200 (:status r)) (pr-str (:body r)))
     (is (contains? state :market))
     (is (not-any? #(contains? state %) [:seller-agreement :discoverability])))))

(deftest the-legacy-webhook-is-gone-on-v2-and-untouched-otherwise
  (on-v2
   (is (= 410 (:status (call (request :post "/v1/gm/handle" {:body {:event/type "market/query-capabilities"}}))))))
  (testing "flag off: the webhook still dispatches"
    (let [r (call (request :post "/v1/gm/handle" {:body {:event/type "market/query-capabilities" :event/data {} :game-state {}}}))]
      (is (not= 410 (:status r)))
      (is (= 404 (:status (call (request :post "/v2/events"))))))))

;; --- the UI's /events ---

(deftest the-ui-events-route-runs-locally-as-the-seat-on-v2
  (on-v2
   (let [seat {:seat-id membership-id :player-name "alex" :player-roles ["audience"]}]
     (with-redefs [gm-identity/require-selected-seat (fn [& _] {:context {:game-id game-id :game-name game-name} :seat seat})]
       (let [r (call (request :post "/events" {:body {:event/type "market/query-capabilities" :event/data {}
                                                      :playerName "mallory"}}))]
         (is (= 200 (:status r)) (pr-str (:body r)))
         (is (empty? @mom-posts) "not forwarded to MoM's /api/v1/action"))
       (testing "the caller protections apply: a seat cannot run a service-only event"
         (is (= 403 (:status (call (request :post "/events" {:body {:event/type "withdrawal/settle" :event/data {}}}))))))))))

;; --- the GM's own calls ---

(deftest the-gms-own-service-events-run-locally-on-v2
  (on-v2
   (let [r (marketplace/submit-service-event game-name "market/query-capabilities" "alex" ["audience"] {})]
     (is (= 200 (:status r)) (pr-str (:body r)))
     (is (empty? @mom-posts)))))

(deftest the-public-read-model-reads-the-gms-own-store-on-v2
  (on-v2
   (let [r (#'h/public-state (request :get "/ui/public-state") game-id game-name)]
     (is (some? r) "state was available without any MoM read (the fake MoM throws on a state read)")
     (is (contains? (:gameState r) :attention)))))
