(ns cmr.search.services.transformer
  "Provides functions for retrieving concepts in a desired format."
  (:require [cmr.system-trace.core :refer [deftracefn]]
            [cmr.transmit.metadata-db :as metadata-db]
            [cmr.umm.core :as ummc]
            [cmr.common.log :as log :refer (debug info warn error)]
            [cmr.common.mime-types :as mt]
            [cmr.common.services.errors :as errors]
            [cmr.common.util :as u]))

(defn- concept->value-map
  "Convert a concept into a map containing metadata in a desired format as well as
  concept-id, revision-id, and possibly collection-concept-id"
  [concept format]
  (let [collection-concept-id (get-in concept [:extra-fields :parent-collection-id])
        concept-format (mt/mime-type->format (:format concept) :fail)
        _ (when (= concept-format :fail)
            (errors/internal-error! "Did not recognize concept format" (pr-str (:format concept))))
        value-map (if (= format concept-format)
                    (select-keys concept [:metadata :concept-id :revision-id])
                    (let [metadata (-> concept
                                       ummc/parse-concept
                                       (ummc/umm->xml format))]
                      (assoc (select-keys concept [:concept-id :revision-id])
                             :metadata
                             metadata)))]
    (if collection-concept-id
      (assoc value-map :collection-concept-id collection-concept-id)
      value-map)))

(deftracefn get-formatted-concept-revisions
  "Get concepts with given concept-id, revision-id pairs in a given format."
  [context concepts-tuples format allow-missing?]
  (info "Transforming" (count concepts-tuples) "concept(s) to" format)
  (let [[t1 concepts] (u/time-execution
                        (metadata-db/get-concept-revisions context concepts-tuples allow-missing?))
        [t2 values] (u/time-execution (mapv #(concept->value-map % format) concepts))]
    (debug "get-concept-revisions time:" t1
           "concept->value-map time:" t2)
    values))

(deftracefn get-latest-formatted-concepts
  "Get latest version of concepts with given concept-ids in a given format."
  [context concept-ids format allow-missing?]
  (info "Getting latest version of" (count concept-ids) "concept(s) in" format "format")
  (let [[t1 concepts] (u/time-execution
                        (metadata-db/get-latest-concepts context concept-ids allow-missing?))
        [t2 values] (u/time-execution (mapv #(concept->value-map % format) concepts))]
    (debug "get-latest-concepts time:" t1
           "concept->value-map time:" t2)
    values))
