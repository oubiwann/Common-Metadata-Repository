(ns cmr.es-spatial-plugin.SpatialScript
  (:gen-class :extends org.elasticsearch.script.AbstractSearchScript
              :constructors {[Object org.elasticsearch.common.logging.ESLogger] []}
              :init init
              :exposes-methods {doc getDoc fields getFields}
              :state data))

(import 'cmr.es_spatial_plugin.SpatialScript)

(defn- -init [intersects-fn logger]
  [[] {:intersects-fn intersects-fn
       :logger logger}])

(defn- intersects-fn [^SpatialScript this]
  (:intersects-fn (.data this)))

(defn- logger [^SpatialScript this]
  (:logger (.data this)))

(defn lookup
  "Temporary helper to lookup a clojure variable dynamically and return it's value.
  The purpose of this is to allow easy testing with the REPL. AOT compiled code prevents
  refreshing at the repl. Vars found in namespaces looked up this way aren't AOT compiled."
  [sym]
  (var-get (find-var sym)))

(defn -run [^SpatialScript this]
  (let [;intersects? cmr.es-spatial-plugin.spatial-script-helper/doc-intersects?
        intersects? (lookup 'cmr.es-spatial-plugin.spatial-script-helper/doc-intersects?)
        ]
    (intersects? (logger this) (.getFields this) (intersects-fn this))))
