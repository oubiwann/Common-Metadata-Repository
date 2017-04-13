(ns cmr.search.site.pages
  "The functions of this namespace are specifically responsible for returning
  ready-to-serve pages."
  (:require
    [cmr.search.site.data :as data]
    [selmer.parser :as selmer]))

(defn render-template-ok
  "A utility function for preparing template pages."
  [template data]
  {:status 200
   :body (selmer/render-file template data)})

(defn home
  "Prepar the home page template."
  [context]
  (render-template-ok
    "templates/index.html"
    (data/get-index context)))

(defn landing-links
  "Prepare the page that links to all landing page links.

  For now, this is just a page with a single link (the EOSDIS collections
  landing pages)."
  [context]
  (render-template-ok
    "templates/landing-links.html"
    (data/get-landing-links context)))

(defn eosdis-landing-links
  "Prepare the page that provides links to top-level EOSDIS providers.

  The intention is for the provider pages linked on this page will have links
  to complete collections."
  [context]
  (render-template-ok
    "templates/eosdis-landing-links.html"
    (data/get-eosdis-landing-links context)))

(defn proivider-tag-landing
  "Prepare the page that provides links to collection landing pages based
  upon a provider and a tag."
  [context provider-id tag]
  (render-template-ok
    "templates/provider-tag-landing-links.html"
    (data/get-provider-tag-landing-links context provider-id tag)))
