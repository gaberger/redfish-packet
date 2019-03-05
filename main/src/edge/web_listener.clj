;; Copyright © 2016, JUXT LTD.

(ns edge.web-listener
  (:require
   [bidi.bidi :refer [tag]]
   [bidi.vhosts :refer [make-handler vhosts-model]]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [cheshire.core :as json]
   [edge.redfish :refer [redfish-systems-routes]]
   [hiccup.core :refer [html]]
   [integrant.core :as ig]
   [ring.util.mime-type :refer [ext-mime-type]]
   [schema.core :as s]
   [selmer.parser :as selmer]
   [yada.resources.resources-resource :refer [new-resources-resource]]
   [yada.resources.webjar-resource :refer [new-webjar-resource]]
   [yada.yada :refer [handler resource] :as yada]))

(defn routes
  "Create the URI route structure for our application."
  [config]
  [""
   [
    (redfish-systems-routes)
    [true (handler nil)]]])

(defmethod ig/init-key :edge/web-listener
  [_ {:edge.web-listener/keys [vhost port] :as config}]
  (let [vhosts-model (vhosts-model [vhost (routes config)])
        listener (yada/listener vhosts-model {:port port})]
    (log/infof "Started HTTP listener on port %s" (:port listener))
    {:listener listener
     ;; Retaining config helps debugging, and console 'annoucement' in dev
     :config (select-keys config [:edge.web-listener/vhost :edge.web-listener/port])}))

(defmethod ig/halt-key! :edge/web-listener [_ {:keys [listener]}]
  (when-let [close (:close listener)]
    (close)))
