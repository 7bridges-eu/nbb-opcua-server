(ns server
  (:require [cljs-bean.core :refer [->clj ->js]]
            [clojure.edn :as edn]
            ["fs" :as fs]
            [promesa.core :as p]
            ["node-opcua" :as opcua]))

(def data-types (->clj (.. opcua -DataType)))

(defn read-sensors
  "Read the sensors' configuration."
  [path]
  (edn/read-string (str (fs/readFileSync path))))

(defn random-range-value
  "Returns a random Variant value between min and max"
  [type [min max] written]
  (if-not (nil? @written)
    @written
    (let [value (rand-nth (range min max))]
      (opcua/Variant. (->js {:dataType (type data-types) :value value})))))

(defn random-pick-value
  "Returns a random Variant value between min and max"
  [type choices written]
  (if-not (nil? @written)
    @written
    (let [value (rand-nth choices)]
      (opcua/Variant. (->js {:dataType (type data-types) :value value})))))

(defn static-value
  "Returns a static Variant value, similar to `constantly`"
  [type value written]
  (if-not (nil? @written)
    @written
    (opcua/Variant. (->js {:dataType (type data-types) :value value}))))

(defn value-fn
  [type [strategy value] written]
  (fn [callback]
    (let [v         (case strategy
                      :random-range (random-range-value type value written)
                      :random-pick  (random-pick-value type value written)
                      :static       (static-value type value written))]
      (js/setTimeout
       (fn []
         (callback nil (opcua/DataValue. (->js {:value           v
                                                :sourceTimestamp (js/Date.now)}))))
       100))))

(defn node-properties
  [machine sensor]
  (let [{:keys [type output]} (:value sensor)
        written-value         (atom nil)]
    (->js {:componentOf             machine
           :nodeId                  (str "s=" (:browse-name sensor))
           :browseName              (:browse-name sensor)
           :dataType                (type data-types)
           :value                   {:refreshFunc (value-fn type output written-value)
                                     :set (fn [v]
                                            (reset! written-value v)
                                            (.. opcua -StatusCodes -Good))}
           :minimumSamplingInterval 1000})))

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
          (.addVariable namespace (node-properties m sensor)))))))

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
