(ns ^{:doc "Integration test for CMR collection search"}
  cmr.system-int-test.search.collection-search-test
  (:require [clojure.test :refer :all]
            [cmr.system-int-test.utils.ingest-util :as ingest]
            [cmr.system-int-test.utils.old-ingest-util :as old-ingest]
            [cmr.system-int-test.utils.search-util :as search]
            [cmr.system-int-test.utils.index-util :as index]))

;; TODO this test uses an older style that we should update

(def provider-collections
  {"CMR_PROV1" [{:short-name "MINIMAL"
                 :version-id "1"
                 :long-name "A minimal valid collection"
                 :entry-title "MinimalCollectionV1"}
                {:short-name "One"
                 :version-id "2"
                 :long-name "One valid collection"
                 :entry-title "OneCollectionV1"}
                {:short-name "Another"
                 :version-id "r1"
                 :long-name "Another valid collection"
                 :entry-title "AnotherCollectionV1"}]

   "CMR_PROV2" [{:short-name "One"
                 :version-id "2"
                 :long-name "One valid collection"
                 :entry-title "OneCollectionV1"}
                {:short-name "Other"
                 :version-id "r2"
                 :long-name "Other valid collection"
                 :entry-title "OtherCollectionV1"}]})


(defn setup
  "set up the fixtures for test"
  []
  (ingest/reset)
  (doseq [provider-id (keys provider-collections)]
    (ingest/create-provider provider-id))
  (doseq [[provider-id collections] provider-collections
          collection collections]
    (old-ingest/update-collection provider-id collection))
  (index/flush-elastic-index))

(defn teardown
  "tear down after the test"
  []
  (ingest/reset))

(defn wrap-setup
  [f]
  (setup)
  (try
    (f)
    (finally (teardown))))

(use-fixtures :once wrap-setup)

(deftest search-by-concept-id
  (testing "Search by existing concept-id."
    (let [references (search/find-refs :collection {:concept-id "C1000000000-CMR_PROV1"})]
      (is (= 1 (count references)))))
  (testing "Search by multiple concept-ids."
    (let [references (search/find-refs :collection {:concept-id ["C1000000000-CMR_PROV1" "C1000000001-CMR_PROV1"]})]
      (is (= 2 (count references)))))
  (testing "Search for non-existent concept-id."
    (let [references (search/find-refs :collection {:concept-id ["C2000000000-CMR_PROV1"]})]
      (is (= 0 (count references)))))
  (testing "Search for both existing and non-existing concept-id."
    (let [references (search/find-refs :collection {:concept-id ["C1000000000-CMR_PROV1" "C1000000001-CMR_PROV1" "C2000000000-CMR_PROV1"]})]
      (is (= 2 (count references))))))

(deftest search-by-provider-id
  (testing "search by non-existent provider id."
    (let [references (search/find-refs :collection {:provider "NON_EXISTENT"})]
      (is (= 0 (count references)))))
  (testing "search by existing provider id."
    (let [references (search/find-refs :collection {:provider "CMR_PROV1"})]
      (is (= 3 (count references)))
      (is (= #{"MinimalCollectionV1" "OneCollectionV1" "AnotherCollectionV1"}
            (set (map :name references))))))
  (testing "search by provider id using wildcard *."
    (let [references (search/find-refs :collection {:provider "CMR_PRO*", "options[provider][pattern]" "true"})]
      (is (= 5 (count references)))
      (is (= #{"MinimalCollectionV1" "OneCollectionV1" "AnotherCollectionV1" "OtherCollectionV1"}
            (set (map :name references))))))
  (testing "search by provider id using wildcard ?."
    (let [references (search/find-refs :collection {:provider "CMR_PROV?", "options[provider][pattern]" "true"})]
      (is (= 5 (count references)))
      (is (= #{"MinimalCollectionV1" "OneCollectionV1" "AnotherCollectionV1" "OtherCollectionV1"}
            (set (map :name references))))))
  (testing "search by provider id case not match."
    (let [references (search/find-refs :collection {:provider "CMR_prov1"})]
      (is (= 0 (count references)))))
  (testing "search by provider id ignore case false"
    (let [references (search/find-refs :collection {:provider "CMR_prov1", "options[provider][ignore_case]" "false"})]
      (is (= 0 (count references)))))
  (testing "search by provider id ignore case true."
    (let [references (search/find-refs :collection {:provider "CMR_prov1", "options[provider][ignore_case]" "true"})]
      (is (= 3 (count references)))
      (is (= #{"MinimalCollectionV1" "OneCollectionV1" "AnotherCollectionV1"}
            (set (map :name references)))))))

(deftest search-by-dataset-id
  (testing "search by non-existent dataset id."
    (let [references (search/find-refs :collection {:dataset_id "NON_EXISTENT"})]
      (is (= 0 (count references)))))
  (testing "search by existing dataset id."
    (let [references (search/find-refs :collection {:dataset_id "MinimalCollectionV1"})]
      (is (= 1 (count references)))
      (let [ref (first references)
            {:keys [name concept-id location]} ref]
        (is (= "MinimalCollectionV1" name))
        (is (re-matches #"C[0-9]+-CMR_PROV1" concept-id)))))
  (testing "search by multiple dataset ids."
    (let [references (search/find-refs :collection {"dataset_id[]" ["MinimalCollectionV1", "AnotherCollectionV1"]})
          dataset-ids (map :name references)]
      (is (= 2 (count references)))
      (is (= #{"MinimalCollectionV1" "AnotherCollectionV1"} (set dataset-ids)))))
  (testing "search by dataset id across different providers."
    (let [references (search/find-refs :collection {:dataset_id "OneCollectionV1"})
          dataset-ids (map :name references)]
      (is (= 2 (count references)))
      (is (= #{"OneCollectionV1"} (set dataset-ids)))))
  (testing "search by dataset id using wildcard *."
    (let [references (search/find-refs :collection {:dataset_id "O*", "options[dataset_id][pattern]" "true"})
          dataset-ids (map :name references)]
      (is (= 3 (count references)))
      (is (= #{"OneCollectionV1" "OtherCollectionV1"} (set dataset-ids)))))
  (testing "search by dataset id case not match."
    (let [references (search/find-refs :collection {:dataset_id "minimalCollectionV1"})]
      (is (= 0 (count references)))))
  (testing "search by dataset id ignore case false."
    (let [references (search/find-refs :collection {:dataset_id "minimalCollectionV1", "options[dataset_id][ignore_case]" "false"})]
      (is (= 0 (count references)))))
  (testing "search by dataset id ignore case true."
    (let [references (search/find-refs :collection {:dataset_id "minimalCollectionV1", "options[dataset_id][ignore_case]" "true"})]
      (is (= 1 (count references)))
      (let [{dataset-id :name} (first references)]
        (is (= "MinimalCollectionV1" dataset-id))))))

(deftest search-by-short-name
  (testing "search by non-existent short name."
    (let [references (search/find-refs :collection {:short_name "NON_EXISTENT"})]
      (is (= 0 (count references)))))
  (testing "search by existing short name."
    (let [references (search/find-refs :collection {:short_name "MINIMAL"})]
      (is (= 1 (count references)))
      (let [ref (first references)
            {:keys [name concept-id location]} ref]
        (is (= "MinimalCollectionV1" name))
        (is (re-matches #"C[0-9]+-CMR_PROV1" concept-id)))))
  (testing "search by multiple short names."
    (let [references (search/find-refs :collection {"short_name[]" ["MINIMAL", "Another"]})
          dataset-ids (map :name references)]
      (is (= 2 (count references)))
      (is (= #{"MinimalCollectionV1" "AnotherCollectionV1"} (set dataset-ids)))))
  (testing "search by short name across different providers."
    (let [references (search/find-refs :collection {:short_name "One"})
          dataset-ids (map :name references)]
      (is (= 2 (count references)))
      (is (= #{"OneCollectionV1"} (set dataset-ids)))))
  (testing "search by short name using wildcard *."
    (let [references (search/find-refs :collection{:short_name "O*", "options[short_name][pattern]" "true"})
          dataset-ids (map :name references)]
      (is (= 3 (count references)))
      (is (= #{"OneCollectionV1" "OtherCollectionV1"} (set dataset-ids)))))
  (testing "search by short name case not match."
    (let [references (search/find-refs :collection {:short_name "minimal"})]
      (is (= 0 (count references)))))
  (testing "search by short name ignore case false."
    (let [references (search/find-refs :collection {:short_name "minimal", "options[short_name][ignore_case]" "false"})]
      (is (= 0 (count references)))))
  (testing "search by short name ignore case true."
    (let [references (search/find-refs :collection {:short_name "minimal", "options[short_name][ignore_case]" "true"})]
      (is (= 1 (count references)))
      (let [{dataset-id :name} (first references)]
        (is (= "MinimalCollectionV1" dataset-id))))))

(deftest search-by-version-id
  (testing "search by non-existent version id."
    (let [references (search/find-refs :collection {:version "NON_EXISTENT"})]
      (is (= 0 (count references)))))
  (testing "search by existing version id."
    (let [references (search/find-refs :collection {:version 1})]
      (is (= 1 (count references)))
      (let [ref (first references)
            {:keys [name concept-id location]} ref]
        (is (= "MinimalCollectionV1" name))
        (is (re-matches #"C[0-9]+-CMR_PROV1" concept-id))
        ;; TODO We should return a URL in the references once the retrieval feature is implemented.
        #_(is (re-matches #"http.*/catalog-rest/echo_catalog/datasets/C[0-9]+-CMR_PROV1$" location)))))
  (testing "search by multiple versions."
    (let [references (search/find-refs :collection {"version[]" ["1", "r1"]})
          dataset-ids (map :name references)]
      (is (= 2 (count references)))
      (is (= #{"MinimalCollectionV1" "AnotherCollectionV1"} (set dataset-ids)))))
  (testing "search by version across different providers."
    (let [references (search/find-refs :collection {:version "2"})
          dataset-ids (map :name references)]
      (is (= 2 (count references)))
      (is (= #{"OneCollectionV1"} (set dataset-ids)))))
  (testing "search by version using wildcard *."
    (let [references (search/find-refs :collection {:version "r*", "options[version][pattern]" "true"})
          dataset-ids (map :name references)]
      (is (= 2 (count references)))
      (is (= #{"OtherCollectionV1" "AnotherCollectionV1"} (set dataset-ids)))))
  (testing "search by version using wildcard ?."
    (let [references (search/find-refs :collection {:version "r?", "options[version][pattern]" "true"})
          dataset-ids (map :name references)]
      (is (= 2 (count references)))
      (is (= #{"OtherCollectionV1" "AnotherCollectionV1"} (set dataset-ids)))))
  (testing "search by version case not match."
    (let [references (search/find-refs :collection {:version "R1"})]
      (is (= 0 (count references)))))
  (testing "search by version ignore case false."
    (let [references (search/find-refs :collection {:version "R1", "options[version][ignore_case]" "false"})]
      (is (= 0 (count references)))))
  (testing "search by version ignore case true."
    (let [references (search/find-refs :collection {:version "R1", "options[version][ignore_case]" "true"})]
      (is (= 1 (count references)))
      (let [{dataset-id :name} (first references)]
        (is (= "AnotherCollectionV1" dataset-id))))))

(deftest search-error-scenarios
  (testing "search by un-supported parameter."
    (try
      (search/find-refs :collection {:unsupported "dummy"})
      (catch clojure.lang.ExceptionInfo e
        (let [status (get-in (ex-data e) [:object :status])
              body (get-in (ex-data e) [:object :body])]
          (is (= 422 status))
          (is (re-matches #".*Parameter \[unsupported\] was not recognized.*" body))))))
  (testing "search by un-supported options."
    (try
      (search/find-refs :collection {:dataset_id "MinimalCollectionV1", "options[dataset_id][unsupported]" "true"})
      (catch clojure.lang.ExceptionInfo e
        (let [status (get-in (ex-data e) [:object :status])
              body (get-in (ex-data e) [:object :body])]
          (is (= 422 status))
          (is (re-matches #".*Option \[unsupported\] for param \[entry_title\] was not recognized.*" body)))))))
