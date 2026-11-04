(ns referron-market.og-test
  "The essay route renders og: tags describing the POST, not the site.

   Referron Market is client-rendered, so without this every shared essay unfurls as the
   homepage — on exactly the surface a coseller link lives in."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [referron-market.handlers :as h]))

(def ^:private find-feed-item #'h/find-feed-item)
(def ^:private og-block #'h/og-block)
(def ^:private essay-response #'h/essay-response)

(def ^:private state
  {:gameState
   {:attention
    {:feed [{:id "bf69ed7f-uuid"
             :payload {:title "Own your economy"
                       :headline "Own your models, your memory, your customers."
                       :featured_image "https://cdn.example/hero.jpg"}}
            {:id "other-uuid"
             :slug "portable-memory"
             :payload {:title "Memory should be yours"
                       :summary "A summary."}}]}}})

(deftest find-feed-item-matches-slug-or-id
  (testing "by slug"
    (is (= "other-uuid" (:id (find-feed-item state "portable-memory")))))

  (testing "by id — the first post was published before slugs existed and its
            UUID URL is already shared; resolving only by slug would 404 it"
    (is (= "bf69ed7f-uuid" (:id (find-feed-item state "bf69ed7f-uuid")))))

  (testing "an item with a slug is STILL reachable by its id"
    (is (= "other-uuid" (:id (find-feed-item state "other-uuid")))))

  (testing "unknown key finds nothing"
    (is (nil? (find-feed-item state "nope")))))

(deftest og-block-shape
  (testing "an image promotes the card to summary_large_image"
    (let [b (og-block {:title "T" :description "D"
                       :image "https://cdn.example/a.jpg" :url "https://x/y"})]
      (is (str/includes? b "og:title\" content=\"T\""))
      (is (str/includes? b "og:image\" content=\"https://cdn.example/a.jpg\""))
      (is (str/includes? b "summary_large_image"))))

  (testing "no image means the small card — claiming a large one with nothing
            to show renders an empty box"
    (let [b (og-block {:title "T" :description "D" :url "https://x/y"})]
      (is (not (str/includes? b "og:image")))
      (is (str/includes? b "content=\"summary\""))))

  (testing "quotes and angle brackets in a title cannot break out of the attribute"
    (let [b (og-block {:title "He said \"go\" <script>"})]
      (is (str/includes? b "&quot;go&quot;"))
      (is (str/includes? b "&lt;script&gt;"))
      (is (not (str/includes? b "<script>")))))

  (testing "an absent description is omitted rather than rendered empty"
    (is (not (str/includes? (og-block {:title "T"}) "og:description")))))

(deftest essay-response-falls-back-to-the-static-shell
  ;; An unfurl is never worth a 502 on the article itself. Every failure mode
  ;; must still serve a readable page.
  (testing "state unavailable"
    (with-redefs [h/public-state (fn [& _] nil)]
      (let [resp (essay-response {:headers {"host" "x"}} "anything")]
        (is (= 200 (:status resp)))
        (is (some? (:body resp))))))

  (testing "item not found"
    (with-redefs [h/public-state (fn [& _] state)]
      (let [resp (essay-response {:headers {"host" "x"}} "no-such-post")]
        (is (= 200 (:status resp))))))

  (testing "an exception mid-render"
    (with-redefs [h/public-state (fn [& _] (throw (RuntimeException. "boom")))]
      (let [resp (essay-response {:headers {"host" "x"}} "portable-memory")]
        (is (= 200 (:status resp)))))))

(deftest essay-response-substitutes-the-real-post
  (with-redefs [h/public-state (fn [& _] state)]
    (let [body (:body (essay-response {:headers {"host" "www.referron.com"}
                                       :scheme :https}
                                      "portable-memory"))]
      (is (string? body))
      (is (str/includes? body "og:title\" content=\"Memory should be yours\""))
      (is (str/includes? body "og:description\" content=\"A summary.\""))
      (testing "exactly one og block survives — the placeholder is replaced,
                not appended, or crawlers see two titles"
        (is (= 1 (count (re-seq #"og:site_name" body))))
        (is (= 1 (count (re-seq #"<!-- og:start -->" body)))))
      (testing "the rest of the document is intact"
        (is (str/includes? body "<!DOCTYPE html>"))
        (is (str/includes? body "</html>"))))))

(deftest manifesto-response-uses-canonical-url
  (with-redefs [h/public-state (fn [& _] {:gameState {:attention {:feed [{:id "manifesto-id"
                                                                           :slug "manifesto"
                                                                           :payload {:title "Referron Market"
                                                                                     :headline "Own the means of thinking."
                                                                                     :featured_image "/assets/featured/manifesto-own-the-means-of-thinking.png"}}]}}})]
    (let [body (:body (essay-response {:headers {"host" "www.referron.com"}
                                       :scheme :https}
                                      "manifesto"
                                      "/manifesto"))]
      (is (str/includes? body "og:title\" content=\"Referron Market\""))
      (is (str/includes? body "og:url\" content=\"https://www.referron.com/manifesto\""))
      (is (str/includes? body "og:image\" content=\"https://www.referron.com/assets/featured/manifesto-own-the-means-of-thinking.png\"")))))

(deftest branded-sharing-assets
  (let [html (slurp (clojure.java.io/resource "ui/index.html"))]
    (is (str/includes? html "/assets/brand/social-card-v2.png"))
    (is (str/includes? html "twitter:image"))
    (doseq [asset ["favicon.png" "apple-touch-icon.png" "referron-logo.png" "inter-latin.woff2"]]
      (is (some? (clojure.java.io/resource (str "ui/assets/brand/" asset))))))
  (with-redefs [h/public-state (fn [& _] state)]
    (let [body (:body (essay-response {:headers {"host" "www.referron.com"} :scheme :https} "portable-memory"))]
      (is (str/includes? body "/assets/brand/social-card-v2.png"))
      (is (str/includes? body "summary_large_image"))
      (is (str/includes? body "twitter:title\" content=\"Memory should be yours")))))
