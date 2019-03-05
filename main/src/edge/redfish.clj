(ns edge.redfish
  (:require [aleph.http :as http]
            [byte-streams :as bs]
            [cheshire.core :as json]
            [edge.packet :refer [reboot-device get-devices get-device transform-device-model]]
            [yada.yada :as yada]
            [yada.security :refer [verify]]
            [hiccup.page :refer [html5]]
            [clojure.string :as str]))

(def creds (atom {}))

(defn verify-auth [[user password]]
  (fn [[user password]]
    (swap! creds conj {:projet-id user :api-key password})
    {:email "bob@acme.com"
     :roles #{:admin}}))

(def options
  {:access-control
   {:realm "accounts"
    :scheme "Basic"
    :verify verify-auth}})

(defn get-computer-collection-schema []
  (-> @(http/get "https://redfish.dmtf.org/schemas/ComputerSystemCollection.json")
      :body
      bs/to-string
      (json/decode true)))

(defn get-computer-system-schema []
  (let [response (-> @(http/get "https://redfish.dmtf.org/schemas/ComputerSystem.json")
                     :body
                     bs/to-string
                     (json/decode true))
        refs (get-in response [:definitions :ComputerSystem :anyOf])
        current (->> (:$ref (last refs)) (re-find #"(\S+)\#.*") fnext)
        schema (-> @(http/get current)
                   :body
                   bs/to-string
                   (json/decode true))]
    schema))

(defn get-required-fields [f]
  (let [schema (f)
        reference (:$ref schema)
        ref-key (->> reference (re-find #"^#\/definitions\/(\w+)") fnext)

        required (assoc {} :required
                        (if-let [req (get-in schema [:definitions (keyword ref-key) :anyOf])]
                          (-> req fnext :required)
                          (-> schema (get-in [:definitions (keyword ref-key) :required]))))
        properties (assoc required :properties
                          (conj (-> schema (get-in [:definitions (keyword ref-key) :properties]) keys)))]
    properties))

(def computer-systems-collection
  {"@odata.id" "/redfish/v1/Systems"
   "@odata.type" "#ComputerSystems.1.00.0.ComputerSystemsCollection"
   "Name" "Computer Systems Collection"})

;TODO Fix this
(defn get-schema-version []
  "1.5.0")

(defn redfish-computer-system-renderer [{:keys [id description name hostname state always_pxe]}]
  (let [schema-version (get-schema-version)]
    {"@odata.id" (str "/redfish/v1/Systems/" id)
     "@odata.type" (str/join "." ["#ComputerSystem" schema-version "ComputerSystem"])
     "Id" id
     "Name" name
     "SystemType" "Physical"
     "AssetTag" id
     "Manufacturer" "Packet"
     "Model" "Packet"
     "SKU" "Packet"
     ;;"SerialNumber" "437XR1138R2"
     ;;"PartNumber" "224071-J23" ; Packet
     "Description"  description
     "UUID"  id
     "HostName" hostname
     "Status" {"State"  state
               "Health"  state
               "HealthRollup" "NA"}
     "PowerState"  state
     "Boot" {"BootSourceOverrideEnabled"  (if-not always_pxe "Once" "Continuous")
             "BootSourceOverrideTarget" "None"
             "BootSourceOverrideMode" "Hdd"
             "UefiTargetBootSourceOverride"  ""
             "BiosVersion" "Unknown"}
     "ProcessorSummary" {"Count" "unknown"
                         "Model" "unknown"
                         "Status" "unknown"
                         "State" "unknown"
                         "Health" "unknown"
                         "HealthRollup" "unknown"}
     "MemorySummary" {"TotalSystemMemoryGiB" "unknown",
                      "TotalSystemPersistentMemoryGiB" "unknown",
                      "MemoryMirroring" "unknown",
                      "Status" {"State" "unknown",
                                "Health" "unknown",
                                "HealthRollup" "unknown"}}}))

;;(clojure.edn/read-string {:readers {'@ str}}
;;                         "@odata.id \"2013-06-08T01:00:00Z\"")

(defn redfish-context [entity]
  {:odata.context (str "/redfish/v1/$metadata#" entity)})

;; Resources

(def redfish-systems-resource
  (yada/resource
   {:produces {:media-type "application/json"}
    :methods {:get
              {:response (fn [ctx]
                           (let [device (get-in ctx [:route-params :id])
                                 vals (transform-device-model device)]
                             (redfish-computer-system-renderer vals)))}}
    :access-control
    {:scheme "Basic"
     :verify (fn [[user password]]
               (if (= password "alice")
                 (do
                   (swap! creds conj {:project-id user :api-key password})
                   {:user "alice"
                    :roles #{:user}})
                 true))
     :authorization {:methods {:get :user}}}}))

(def redfish-systems-list
    (yada/resource
     (merge
      options
      {:produces {:media-type "application/json"}
       :methods {:get
                 {:response (fn [ctx]
                              (let [project-id edge.packet/project-id
                                    retval (edge.packet/get-devices project-id)]
                                retval))}}
       :access-control
       {:scheme "Basic"
        :verify (fn [[user password]]
                  (if (= password "alice")
                    (do
                      (swap! creds conj {:project-id user :api-key password})
                      {:user "alice"
                       :roles #{:user}})
                    true))
        :authorization {:methods {:get :user}}}})))


#_(def redfish-computer-systems-collection-response
    (yada/resource
     {:produces {:media-type "application/json"}
      :methods {:get
                {:response
                 (fn [ctx]
                   (let [payload (get-devices project-id)
                         dev-count (count (:devices payload))
                         ids (mapv :id (:devices payload))]
                     (merge
                      computer-systems-collection
                      {"@odata.count" dev-count}
                      {"Members"
                       (mapv #(assoc {} "@odata.id" (str "/redfish/v1/Systems/" %)) ids)})))}}}))

#_(def redfish-packet-reset
    (yada/resource
     {:produces {:media-type "application/json"}
      :methods {:post
                {:response
                 (fn [ctx]
                   (let [device (get-in ctx [:route-params :id])]
                     (reboot-device device)))}}}))

(defn redfish-systems-routes []
  ["/redfish/v1/"
   [["Systems" redfish-computer-systems-collection-response]
    ["Systems/" [["" redfish-computer-systems-collection-response]
                 [[:id "/Actions/ComputerSystem.Reset"] redfish-packet-reset]
                 [[:id ""] redfish-systems-resource]]]
    ["Sessions" (yada/handler "Sessions")]
    ["Accounts" (yada/handler "Accounts")]]])

; (defn- restricted-content [ctx]
;   (html5
;    [:body
;     [:h1 (format "Hello %s!" (get-in ctx [:authentication "default" :user]))]
;     [:h2 (format "Password %s!" (get-in ctx [:authentication "default" :password]))]
;     [:p "You're accessing a restricted resource!"]
;     [:pre (pr-str (get-in ctx [:authentication "default"]))]
;     [:pre ctx]]))


; (defmethod verify :edge.redfish/custom-static
;   [ctx scheme]
;   (let [password (get-in ctx [:authentication "default" :password])]
;     (if (= password "alice")
;       {:user "alice"
;        :roles #{:user}}
;        {:user password
;        :roles #{:user}})))
;        ; true)))


;; (def basic-auth-resource-example
;;   (yada/resource
;;    {:id :edge.packet/basic-authn-example
;;     :methods {:get {:produces "application/json"
;;                     :response (fn [ctx] (restricted-content ctx))}}

;;     :access-control
;;     {:scheme "Basic"
;;      :verify (fn [[user password]]
;;                   (if (= password "alice")
;;                     (do
;;                       (swap! creds conj {:project-id user :api-key password}))
;;                     {:user "alice"
;;                      :roles #{:user}}
;;                      true))
;;      :authorization {:methods {:get :user}}}}))


; (defn redfish-systems-routes []
;   ["/redfish/v1/"
;   [
;     ["Systems" basic-auth-resource-example]
;     ["Sessions" (yada/handler "Sessions")]
;     ["Accounts" (yada/handler "Accounts")]
;   ]
;   ]
;)
