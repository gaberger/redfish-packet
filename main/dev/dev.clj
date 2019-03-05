;; Copyright © 2016-2018, JUXT LTD.
(ns dev
  (:require
   [clojure.core.async :as a :refer [>! <! >!! <!! chan buffer dropping-buffer sliding-buffer close! timeout alts! alts!! go-loop]]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [clojure.reflect :refer [reflect]]
   [clojure.repl :refer [apropos dir doc find-doc pst source]]
   [clojure.test :refer [run-all-tests]]
   [clojure.tools.namespace.repl :refer [refresh refresh-all]]
   [edge.system :as system]
   [integrant.repl :refer [clear halt prep init reset reset-all]]
   [integrant.repl.state :refer [system]]
   [io.aviso.ansi]
   [yada.test :refer [response-for]]
   ))

(defn go []
  (let [res (integrant.repl/go)]
    (println (io.aviso.ansi/yellow
               (format "[Edge] Website ready: %s"
                       (-> system :edge/web-listener :config))))
    (println (io.aviso.ansi/bold-yellow "[Edge] Now make code changes, then enter (reset) here"))
    res))

(integrant.repl/set-prep! #(system/system-config :dev))

(defn test-all []
  (run-all-tests #"edge.*test$"))

(defn reset-and-test []
  (reset)
  (time (test-all)))

(defn cljs-repl
  "Start a ClojureScript REPL"
  []
  (eval
    `(do
       (require 'figwheel-sidecar.repl-api)
       (figwheel-sidecar.repl-api/cljs-repl))))

;; REPL Convenience helpers


(defn executor-stats []
  (->> system :edge/executor .getStats manifold.executor/stats->map))
