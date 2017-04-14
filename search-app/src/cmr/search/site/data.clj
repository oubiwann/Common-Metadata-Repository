(ns cmr.search.site.data
  "The functions of this namespace are specifically responsible for generating
  data structures to be consumed by site page templates.

  Of special note: this namespace and its sibling `page` namespace are only
  ever meant to be used in the `cmr.search.site` namespace, particularly in
  support of creating site routes for access in a browser.

  Under no circumstances should `cmr.search.site.data` be accessed from outside
  this context; the data functions defined herein are specifically for use
  in page templates, structured explicitly for their needs."
  (:require
    [cmr.common-app.services.search.query-execution :as query-exec]
    [cmr.search.services.query-service :as query-svc]
    [cmr.transmit.metadata-db :as mdb]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Data utility functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-tag-short-name
  "A utility function used to present a more human-friendly version of a tag."
  [tag]
  (case tag
    "gov.nasa.eosdis" "EOSDIS"))

(defn provider-data
  "Create a provider data structure suitable for template iteration to
  generate links.

  Note that the given tag will be used to filter provider collections data
  that is used on the destination page."
  [tag provider-data]
  {:id (:provider-id provider-data)
   :name (:provider-id provider-data)
   :tag tag})

(defn get-doi
  "Extract the DOI information from a collection item."
  [item]
  (get-in item [:umm "DOI"]))

(defn has-doi?
  "Determine wheter a collection item has a DOI entry."
  [item]
  (if-not (nil? (get-doi item))
    true
    false))

(defn doi-link
  "Given DOI umm data of the form `{:doi <STRING>}`, generate a landing page
  link."
  [doi-data]
  (format "http://dx.doi.org/%s" (doi-data "DOI")))

(defn cmr-link
  "Given a CMR host and a concept ID, return the collection landing page for
  the given id."
  [cmr-host concept-id]
  (format "https://%s/concepts/%s.html" cmr-host concept-id))

(defn make-href
  "Create the `href` part of a landing page link."
  [item]
  (if (has-doi? item)
    (doi-link (get-doi item))
    (cmr-link "host" (get-in item [:meta :concept-id]))))

(defn get-long-name
  "Get a collection item's long name."
  [item]
  (or (get-in item [:umm "EntryTitle"])
      (get-in item [:meta :concept-id])))

(defn get-short-name
  "Get a collection item's short name, if it exists."
  [item]
  (if-let [short-name (get-in item [:umm "ShortName"])]
    (format " (%s)" short-name)
    ""))

(defn make-text
  "Create the `text` part of a landing page link."
  [item]
  (format "%s%s" (get-long-name item) (get-short-name item)))

(defn make-link
  "Given a single item from a query's collections, generate an appropriate
  landing page link.

  A generated link has the form `{:href ... :text ...}`."
  [item]
  {:href (make-href item)
   :text (make-text item)})

(defn make-links
  "Given a collection from an elastic search query, generate landing page
  links appropriate for the collection."
  [coll]
  (map make-link coll))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Page data functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-index
  "Return the data for the index page (none for now)."
  [context]
  {})

(defn get-landing-links
  "Provide the list of links that will be rendered on the general landing
  pages page."
  [context]
  {:links [{:href "/site/collections/landing-pages/eosdis"
            :text "Landing Pages for EOSDIS Collections"}]})

(defn get-eosdis-landing-links
  "Generate the data necessary to render EOSDIS landing page links (basically,
  a list of providers)."
  [context]
  (let [providers (mdb/get-providers context)]
    {:providers (map (partial provider-data "gov.nasa.eosdis") providers)}))

(defn get-provider-tag-landing-links
  "Generate the data necessary to render EOSDIS landing page links."
  [context provider-id tag]
  (let [query (query-svc/make-concepts-query
                context
                :collection
                {:tag_key tag
                 :provider provider-id
                 :result-format {:format :umm-json-results}})
        coll (:items (query-exec/execute-query context query))]
    {:provider-name provider-id
     :provider-id provider-id
     :tag-name (get-tag-short-name tag)
     :links (make-links coll)}))

