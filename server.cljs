(ns server
  (:require [cljs-bean.core :refer [->clj ->js]]
            [clojure.edn :as edn]
            ["fs" :as fs]
            ["node-opcua" :as opcua]
            [promesa.core :as p]))

(def data-types (->clj (.. opcua -DataType)))

(defn read-sensors
  "Read the sensors' configuration."
  [path]
  (edn/read-string (str (fs/readFileSync path))))

(defn random-range-value
  "Returns a random Variant value between min and max"
  [type [min max]]
  (fn []
    (let [value (rand-nth (range min max))]
      (opcua/Variant. (->js {:dataType (type data-types) :value value})))))

(defn random-pick-value
  "Returns a random Variant value between min and max"
  [type choices]
  (fn []
    (let [value (rand-nth choices)]
      (opcua/Variant. (->js {:dataType (type data-types) :value value})))))

(defn static-value
  "Returns a static Variant value, similar to `constantly`"
  [type value]
  (fn []
    (opcua/Variant. (->js {:dataType (type data-types) :value value}))))

(defn value-fn
  [type [strategy value]]
  (case strategy
    :random-range (random-range-value type value)
    :random-pick  (random-pick-value type value)
    :static       (static-value type value)))

(defn populate-sensors
  "Configures the server's sensor"
  [server sensors]
  (js/console.log "#### Populate sensors...")
  (let [address-space (.. server -engine -addressSpace)
        namespace     (.registerNamespace address-space "FakeOPC")]
    (doseq [machine (keys sensors)]
      (let [m (.addObject namespace (->js {:browseName  machine
                                           :organizedBy (.. address-space -rootFolder -objects)}))]
        (doseq [sensor (get sensors machine)]
          (let [{:keys [type output]} (:value sensor)]
            (.addVariable namespace (->js {:componentOf             m
                                           :browseName              (:browse-name sensor)
                                           :nodeId                  (str "s=" (:browse-name sensor))
                                           :dataType                (type data-types)
                                           :value                   {:get (value-fn type output)}
                                           :minimumSamplingInterval 1000}))))))))

(def server-config
  (->js {:port              4840
         :alternateHostname "127.0.0.1"
         :resourcePath      ""
         :nodeset_filename  [(.. opcua -nodesets -standard)]
         :allowAnonymous    true
         :securityModes     [(.. opcua -MessageSecurityMode -None)]
         :securityPolicies  [(.. opcua -SecurityPolicy -None)]}))

(defn -main
  []
  (let [server  (opcua/OPCUAServer. server-config)
        sensors (read-sensors "./sensors.edn")]
    (try
      (p/do
        (js/console.log "#### Init server sensors...")
        (.initialize server)
        (populate-sensors server sensors)
        (js/console.log "#### Start server...")
        (.start server))
      (catch ExceptionInfo e
        (js/console.log "!!!! Error" e)))))
