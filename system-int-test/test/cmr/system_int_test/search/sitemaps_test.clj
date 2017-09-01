(ns cmr.system-int-test.search.sitemaps-test
  (:require
   [clj-http.client :as client]
   [clj-xml-validation.core :as xmlv]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer :all]
   [cmr.mock-echo.client.echo-util :as e]
   [cmr.search.services.content-service :as content-service]
   [cmr.search.site.static :as static]
   [cmr.system-int-test.data2.core :as d]
   [cmr.system-int-test.utils.search-util :as search]
   [cmr.system-int-test.system :as s]
   [cmr.system-int-test.utils.index-util :as index]
   [cmr.system-int-test.utils.ingest-util :as ingest]
   [cmr.system-int-test.utils.tag-util :as tags]
   [cmr.transmit.config :as transmit-config]
   [cmr.umm-spec.models.umm-common-models :as cm]
   [cmr.umm-spec.test.expected-conversion :as exp-conv]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Constants and general utility functions for the tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private test-collections (atom {}))
(def ^:private validate-sitemap-index
  (xmlv/create-validation-fn (io/resource "sitemaps/siteindex.xsd")))
(def ^:private validate-sitemap
  (xmlv/create-validation-fn (io/resource "sitemaps/sitemap.xsd")))

(defn- add-token-header
  ([token]
   (add-token-header token {}))
  ([token options]
   (assoc-in options [:headers transmit-config/token-header] token)))

(defn- get-response
  ([url-path]
   (get-response url-path {}))
  ([url-path options]
   (-> (transmit-config/application-public-root-url :search)
       (str url-path)
       (client/get options))))

(defn- do-permission-assertions
  [test-section url-path]
  (testing test-section
    (let [no-perms-error ["You do not have permission to perform that action."]]
      (testing "anonymous"
        (let [response (get-response url-path {:throw-exceptions? false})]
          (is (= 401 (:status response)))
          (is (= no-perms-error (search/safe-parse-error-xml (:body response))))))
      (testing "nil token"
        (let [response (get-response
                        url-path
                        (add-token-header nil {:throw-exceptions? false}))]
          (is (= 401 (:status response)))
          (is (= no-perms-error (search/safe-parse-error-xml (:body response))))))
      (testing "fake token"
        (let [response (get-response
                        url-path
                        (add-token-header "ABC" {:throw-exceptions? false}))]
          (is (= 401 (:status response)))
          (is (= ["Token ABC does not exist"]
                 (search/safe-parse-error-xml (:body response))))))
      (testing "regular user token"
        (let [response (get-response
                        url-path
                        (add-token-header (e/login (s/context) "user")
                                          {:throw-exceptions? false}))]
          (is (= 401 (:status response)))
          (is (= no-perms-error (search/safe-parse-error-xml (:body response))))))
      (testing "admin"
        (let [admin-group-id (e/get-or-create-group (s/context) "admin-group")
              admin-user-token (e/login (s/context) "admin-user" [admin-group-id])
              _ (e/grant-group-admin (s/context) admin-group-id :update)
              ;; Need to clear the ACL cache to get the latest ACLs from mock-echo
              _ (search/clear-caches)
              response (get-response url-path (add-token-header admin-user-token))]
          (is (= 200 (:status response))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Functions for creating testing data
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- setup-collections
  "A utility function that generates testing collections data with the bits we
  need to test."
  []
  (let [[c1-p1 c2-p1 c3-p1
         c1-p2 c2-p2 c3-p2] (doall (for [p ["PROV1" "PROV2"]
                                         n (range 1 4)]
                                     (d/ingest-umm-spec-collection
                                      p
                                      (-> exp-conv/example-collection-record
                                          (assoc :ShortName (str "s" n))
                                          (assoc :EntryTitle (str "Collection Item " n)))
                                      {:format :umm-json
                                       :accept-format :json})))
         _ (index/wait-until-indexed)
         [c1-p3 c2-p3 c3-p3] (doall (for [n (range 4 7)]
                                     (d/ingest-umm-spec-collection
                                       "PROV3"
                                       (-> exp-conv/example-collection-record
                                           (assoc :ShortName (str "s" n))
                                           (assoc :EntryTitle (str "Collection Item " n))
                                           (assoc :DOI (cm/map->DoiType
                                                         {:DOI (str "doi" n)
                                                          :Authority (str "auth" n)})))
                                       {:format :umm-json
                                        :accept-format :json})))]
    (reset! test-collections
            {"PROV1" (map :concept-id [c1-p1 c2-p1 c3-p1])
             "PROV2" (map :concept-id [c1-p2 c2-p2 c3-p2])
             "PROV3" (map :concept-id [c1-p3 c2-p3 c3-p3])})
    ;; Wait until collections are indexed so tags can be associated with them
    (index/wait-until-indexed)
    ;; Use the following to generate html links that will be matched in tests
    (let [user-token (e/login (s/context) "user")
          notag-colls [c1-p1 c1-p2 c1-p3]
          nodoi-colls [c1-p1 c2-p1 c3-p1 c1-p2 c2-p2 c3-p2]
          doi-colls [c1-p3 c2-p3 c3-p3]
          all-colls (into nodoi-colls doi-colls)
          tag-colls [c2-p1 c2-p2 c2-p3 c3-p1 c3-p2 c3-p3]
          tag (tags/save-tag
                user-token
                (tags/make-tag {:tag-key "gov.nasa.eosdis"})
                tag-colls)]
      (index/wait-until-indexed)
      ;; Sanity checks
      (assert (= (count notag-colls) 3))
      (assert (= (count nodoi-colls) 6))
      (assert (= (count doi-colls) 3))
      (assert (= (count tag-colls) 6))
      (assert (= (count all-colls) 9)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Fixtures
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def collections-fixture
  (fn [f]
    (setup-collections)
    (search/refresh-collection-metadata-cache)
    (f)))

(use-fixtures :once (join-fixtures
                     [(ingest/reset-fixture {"provguid1" "PROV1"
                                             "provguid2" "PROV2"
                                             "provguid3" "PROV3"})
                      tags/grant-all-tag-fixture
                      collections-fixture]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest sitemap-master
  (let [response (get-response "sitemap.xml")
        body (:body response)]
    (testing "XML validation"
      (is (xmlv/valid? (validate-sitemap-index body))))
    (testing "presence and content of master sitemap.xml index file"
      (is (= (:status response) 200))
      (is (string/includes? body "/site/sitemap.xml</loc>"))
      (is (string/includes? body "/collections/directory/PROV1/gov.nasa.eosdis/sitemap.xml</loc>"))
      (is (string/includes? body "/collections/directory/PROV2/gov.nasa.eosdis/sitemap.xml</loc>"))
      (is (string/includes? body "/collections/directory/PROV3/gov.nasa.eosdis/sitemap.xml</loc>")))))

(deftest sitemap-top-level
  (let [response (get-response "site/sitemap.xml")
        body (:body response)]
    (testing "XML validation"
      (is (xmlv/valid? (validate-sitemap body))))
    (testing "presence and content of sitemap.xml file"
      (is (= (:status response) 200))
      (is (string/includes? body "/docs/search/api</loc>"))
      (is (string/includes? body "</changefreq>"))
      (is (string/includes? body "/collections/directory</loc>"))
      (is (string/includes? body "/collections/directory/eosdis</loc>"))
      (is (string/includes? body "/collections/directory/PROV1/gov.nasa.eosdis</loc>"))
      (is (string/includes? body "/collections/directory/PROV2/gov.nasa.eosdis</loc>"))
      (is (string/includes? body "/collections/directory/PROV3/gov.nasa.eosdis</loc>")))))

(deftest sitemap-provider1
  (let [provider "PROV1"
        tag "gov.nasa.eosdis"
        url-path (format
                  "site/collections/directory/%s/%s/sitemap.xml"
                  provider tag)
        response (get-response url-path)
        body (:body response)
        colls (@test-collections "PROV1")]
    (testing "XML validation"
      (is (xmlv/valid? (validate-sitemap body))))
    (testing "presence and content of sitemap.xml file"
      (is (= 200 (:status response)))
      (is (string/includes? body "</changefreq>"))
      (is (string/includes?
           body (format "concepts/%s.html</loc>" (second colls))))
      (is (string/includes?
           body (format "concepts/%s.html</loc>" (last colls)))))
    (testing "the collections not tagged with eosdis shouldn't show up"
      (is (not (string/includes?
                body (format "%s.html</loc>" (first colls))))))))

(deftest sitemap-provider2
  (let [provider "PROV2"
        tag "gov.nasa.eosdis"
        url-path (format
                  "site/collections/directory/%s/%s/sitemap.xml"
                  provider tag)
        response (get-response url-path)
        body (:body response)
        colls (@test-collections "PROV2")]
    (testing "XML validation"
      (is (xmlv/valid? (validate-sitemap body))))
    (testing "presence and content of sitemap.xml file"
      (is (= 200 (:status response)))
      (is (string/includes? body "</changefreq>"))
      (is (string/includes?
           body (format "concepts/%s.html</loc>" (second colls))))
      (is (string/includes?
           body (format "concepts/%s.html</loc>" (last colls)))))
    (testing "the collections not tagged with eosdis shouldn't show up"
      (is (not (string/includes?
                body (format "%s.html</loc>" (first colls))))))))

(deftest sitemap-provider3
  (let [provider "PROV3"
        tag "gov.nasa.eosdis"
        url-path (format
                  "site/collections/directory/%s/%s/sitemap.xml"
                  provider tag)
        response (get-response url-path)
        body (:body response)
        colls (@test-collections "PROV3")]
    (testing "presence and content of sitemap.xml file"
      (is (= 200 (:status response)))
      (is (not (string/includes? body "http://dx.doi.org/doi5</loc>")))
      (is (not (string/includes? body "http://dx.doi.org/doi5</loc>"))))
    (testing "the collections not tagged with eosdis shouldn't show up"
      (is (not (string/includes?
                body (format "%s.html</loc>" (first colls))))))))

(deftest regeneration-permissions
  (do-permission-assertions
   "master sitemap" "sitemap.xml?regenerate=true")
  (do-permission-assertions
   "content sitemap" "site/sitemap.xml?regenerate=true")
  (do-permission-assertions
   "provider/tag sitemap"
   "site/collections/directory/PROV1/gov.nasa.eosdis/sitemap.xml?regenerate=true"))
