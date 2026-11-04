(ns referron-market.discoverability
  "Referron Market's allowlisted public discovery adapter and server-rendered public pages."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [cheshire.core :as json]
            [game.protocols.discoverability :as d]
            [referron-market.storefront :as storefront]
            [referron-market.catalog :as catalog])
  (:import [org.commonmark.parser Parser]
           [org.commonmark.renderer.html HtmlRenderer]
           [java.time Instant LocalDate]
           [java.net URLEncoder URI]
           [org.jsoup Jsoup]
           [org.jsoup.nodes Element]))

(defn read-resource [path] (some-> (io/resource path) slurp))
(def defaults (edn/read-string (read-resource "discovery/profile.edn")))
(defn- md-html [text]
  (.render (.build (HtmlRenderer/builder)) (.parse (.build (Parser/builder)) (str text))))
(defn- enc [s] (str/replace (URLEncoder/encode (str s) "UTF-8") "+" "%20"))
(defn- canonical-date [x]
  (when x (try (if (number? x) (str (Instant/ofEpochMilli (long x))) (str (Instant/parse (str x))))
               (catch Exception _ (try (str (LocalDate/parse (str x))) (catch Exception _ nil))))))
(defn- link [path title] (str "<a href=\"" (d/escape-html path) "\">" (d/escape-html title) "</a>"))
(defn- links [rs] (str "<ul>" (apply str (map #(str "<li>" (link (:path %) (:title %)) "</li>") rs)) "</ul>"))
(defn- paragraph [s] (str "<p>" (d/escape-html s) "</p>"))
(defn- legal-html [function-name]
  (let [pattern (re-pattern (str "(?s)function " function-name "\\(\\)\\{\\s*return legalPage\\('[^']+', `(.+?)`\\);"))]
    (-> (or (second (re-find pattern (read-resource "ui/index.html"))) "")
        (str/replace "${esc(LEGAL_ENTITY)}" "Referron"))))
(defn- guide [path title file]
  (let [md (read-resource (str "discovery/" file ".md"))]
    {:path path :title title :type "page" :description (case file
                                                       "coselling" "Build useful relationships, productive networks, and a long tail of earnings."
                                                       "agents" "Connect an authorized agent to Referron Market and discover its supported actions."
                                                       "system" "Referron Market identities, protocols, and community roles."
                                                       "market" "Discover products, apply as a seller, and understand agentic commerce.")
     :group "Agent guides" :html (md-html md) :markdown md}))
(defn context
  "Receives full state but selects public fields explicitly. Never expose this input."
  [state]
  (let [state (catalog/with-preview state)
        site-profile (d/profile {:profile (assoc defaults :origin (or (System/getenv "APP_BASE_URL") (:origin defaults)))} (:discoverability state))
        intro (d/narrative {:narrative (read-resource "discovery/narrative.md")} (:discoverability state))
        posts (->> (get-in state [:attention :feed])
                   (keep (fn [it]
                           (let [p (:payload it) slug (str (or (:slug it) (:slug p) (:id it)))
                                 path (if (= slug "manifesto") "/manifesto" (str "/insights/" (enc slug)))]
                             (when (and (seq (:title p)) (not= "" slug))
                               {:path path :aliases (vec (distinct (remove #{path} [(str "/insights/" (enc slug)) (str "/insights/" (enc (:id it)))])))
                                :type "article" :group "Insights" :title (:title p) :author (:author it)
                                :description (or (:headline p) (:summary p) "") :image (:featured_image p)
                                :html (or (:body p) (paragraph (:summary p)))
                                :published-at (canonical-date (or (:published-at it) (:timestamp it)))
                                :modified-at (canonical-date (or (:updated-at it) (:ts (last (:edits it))) (:published-at it) (:timestamp it)))})))) vec)
        caps (->> (get-in state [:market :capabilities]) vals
                  (filter #(and (:seller %) (= "active" (name (or (:status %) :active))))) vec)
        profiles (merge (zipmap (map :seller caps) (repeat {})) (storefront/public-profiles state))
        products (mapv (fn [c]
                         (let [enabled (not= false (get-in c [:commerce :checkout-enabled]))
                               price (get-in c [:terms :price]) seller (str (:seller c))]
                           {:path (str "/market/" (enc seller) "/products/" (enc (:id c)))
                            :type (if (= "service" (name (or (:kind c) :product))) "service" "product")
                            :title (:title c) :description (or (:description c) "") :group "Products"
                            :image (get-in c [:images 0 :url]) :modified-at (canonical-date (:updated-at c))
                            :offer (when price {:available? enabled :currency (:currency price)
                                                :price (when (number? (:amount price)) (/ (bigdec (:amount price)) 100M))})
                            :html (str (paragraph (:description c))
                                       (when price (paragraph (str (:currency price) " " (when (number? (:amount price)) (/ (bigdec (:amount price)) 100M)))))
                                       (when-not enabled (paragraph "Coming soon! This preview cannot be purchased yet."))
                                       (paragraph (str "Sold by " (or (:display-name (get profiles seller)) seller)))
                                       (link (str "/market/" (enc seller)) "Visit seller storefront"))})) caps)
        stores (mapv (fn [[id p]]
                       (let [id (name id) goods (filter #(= id (:seller %)) caps)
                             ready (some #(not= false (get-in % [:commerce :checkout-enabled])) goods)]
                         {:path (str "/market/" (enc id)) :type "page" :group "Storefronts"
                          :title (or (:display-name p) id) :description (or (:tagline p) (:description p) (str "Products from " id))
                          :image (:logo-url p)
                          :html (str (paragraph (:description p))
                                     (when-not ready (paragraph "Purchasing on Referron Market is coming soon."))
                                     (when (seq (:website-url p)) (link (:website-url p) "Visit seller website"))
                                     (links (filter #(str/starts-with? (:path %) (str "/market/" (enc id) "/products/")) products)))})) (sort-by (fn [[id p]] [(if (= "yvatar" (name id)) 0 1) (or (:display-name p) (name id))]) profiles))
        guides [(guide "/guides/coselling" "How coselling works" "coselling")
                (guide "/guides/agents" "Connect your agent" "agents")
                (guide "/guides/system" "The Referron Market system" "system")
                (guide "/guides/market" "Agentic commerce on Referron Market" "market")]
        fixed [{:path "/" :title "Good connections. Great possibilities." :description (:summary site-profile)
                :html (str (md-html intro) (link "/coselling" "Become a Coseller")
                           "<h2>Insights</h2>" (links posts) "<h2>Market</h2>" (links stores))}
               {:path "/insights" :title "Insights" :description "Ideas for building useful connections." :html (if (seq posts) (links posts) (paragraph "No insights yet. The first perspectives are being written."))}
               {:path "/market" :title "Market" :description "Discover products, services, and the people behind them." :html (links stores)}
               {:path "/coselling" :title "Become a Coseller" :description "Put your expertise and your agent to work in a trusted referral community."
                :html (str (md-html (read-resource "discovery/coselling.md")) (link "/join" "Join Referron Market"))}
               {:path "/seller-agreement" :title "Seller Agreement" :description "Apply to become an Referron Market Seller and review the participation agreement."
                :html (str (paragraph "Become a Seller. Sign in to apply; Referron Market reviews your application and presents commercial terms separately before activation.")
                           (link "/join" "Sign in to apply") (read-resource "ui/assets/legal/seller-agreement-v1.html"))}
               {:path "/privacy" :title "Privacy Policy" :description "How Referron handles your information." :html (legal-html "viewPrivacy")}
               {:path "/terms" :title "Terms of Service" :description "Terms for participating in Referron Market." :html (legal-html "viewTerms")}]
        readiness (:coseller-readiness defaults)
        status-html (str "<h2>Current availability</h2>" (paragraph (str "Market status: " (:market-status readiness)
                                                     ". Earnings enabled: " (:earnings-enabled readiness) ". Check each offer's checkout availability.")))
        rs (mapv #(if (#{"/guides/coselling" "/coselling"} (:path %))
                    (-> % (update :html str status-html)
                        (update :markdown (fn [md] (when md (str md "\n\n## Current availability\n\nMarket status: " (:market-status readiness)
                                                            ". Earnings enabled: " (:earnings-enabled readiness) "."))))) %) (concat fixed guides posts stores products))]
    {:profile (select-keys site-profile d/profile-keys) :narrative (read-resource "discovery/narrative.md")  :resources rs
     :audit-findings
     (vec (mapcat (fn [r]
                    (let [body (Jsoup/parseBodyFragment (d/clean-html (:origin site-profile) (:html r)))
                          sources (concat (when (:image r) [(:image r)])
                                          (map #(.attr ^Element % "src") (.select body "img[src]")))]
                      (for [src sources
                            :let [url (d/safe-image (:origin site-profile) src)
                                  uri (when url (URI. url))]
                            :when (and uri (= (.getHost (URI. (:origin site-profile))) (.getHost uri))
                                       (str/starts-with? (.getPath uri) "/assets/")
                                       (nil? (io/resource (str "ui" (.getPath uri)))))]
                        {:severity "error" :code "missing-image" :path (:path r)
                         :message (str "First-party image is unavailable: " (.getPath uri))}))) rs))}))

(defn snapshot [state] {:context (context state) :state (or (:discoverability state) {})})
(defn social-metadata [profile resource]
  (let [image (or (:image resource) (:image profile))
        default? (= image "/assets/brand/social-card-v2.png")
        absolute (when image (d/safe-image (:origin profile) image))
        alt (if default? "Referron Market: Good connections. Great possibilities. The Referron mark connects a network of shared opportunity." (:title resource))
        tag (fn [attr key value] (str "<meta " attr "=\"" key "\" content=\"" (d/escape-html value) "\">\n"))]
    (str (d/metadata-html profile resource)
         (when absolute
           (str (when (str/starts-with? absolute "https:") (tag "property" "og:image:secure_url" absolute))
                (tag "property" "og:image:alt" alt)
                (tag "name" "twitter:image:alt" alt)
                (when default?
                  (str (tag "property" "og:image:type" "image/png")
                       (tag "property" "og:image:width" "1200")
                       (tag "property" "og:image:height" "630"))))))))

(defn render-page [{:keys [context state]} path]
  (when-let [r (d/resource context path)]
    (let [p (d/profile context state)
          tpl (read-resource "ui/index.html")
          nav (str "<header><div class=\"bar\">" (link "/" "Referron Market") "<nav aria-label=\"Main navigation\">"
                   (link "/insights" "Insights") " " (link "/market" "Market") " " (link "/join" "Join") "</nav></div></header>")
          content (str nav "<main id=\"main\" tabindex=\"-1\">" (d/page-html p r) "</main><footer>"
                       (link "/insights" "Insights") " · " (link "/manifesto" "Manifesto") " · " (link "/coselling" "Become a Coseller") " · "
                       (link "/seller-agreement" "Seller Agreement") " · " (link "/privacy" "Privacy") " · " (link "/terms" "Terms") "</footer>")
          body (-> tpl
                   (str/replace #"(?s)<title>.*?</title>" "")
                   (str/replace #"<meta name=\"description\"[^>]*>" "")
                   (str/replace #"(?s)<!-- og:start -->.*?<!-- og:end -->" (str/re-quote-replacement (social-metadata p r)))
                   (str/replace #"(?s)<div id=\"app\">.*?</div>\s*<div class=\"toast\""
                                (str/re-quote-replacement (str "<div id=\"app\" data-server-rendered=\"true\">" content "</div><div class=\"toast\""))))]
      {:status 200 :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store, max-age=0"
                            "Link" (str "<" (d/markdown-path (:path r)) ">; rel=\"alternate\"; type=\"text/markdown\", </llms.txt>; rel=\"describedby\"")}
       :body body})))
(defn artifact-response [{:keys [context state]} path]
  ;; Shared discovery requires HTTPS. Local development may use loopback HTTP;
  ;; adapt its public artifacts locally without changing the shared protocol.
  (let [origin (get-in context [:profile :origin])
        local? (and origin (re-matches #"http://(?:localhost|127\.0\.0\.1)(?::[0-9]+)?" origin))
        secure-origin (when local? (str/replace-first origin "http:" "https:"))
        context (if local? (assoc-in context [:profile :origin] secure-origin) context)]
    (when-let [a (d/artifact context state path)]
      {:status 200 :headers {"Content-Type" (:content-type a) "Cache-Control" "no-store, max-age=0"
                            "Link" "</llms.txt>; rel=\"describedby\""}
       :body (if local? (str/replace (:body a) secure-origin origin) (:body a))})))
