(ns server
  (:require [cljs-bean.core :refer [->clj ->js]]
            [promesa.core :as p]
            ["node-opcua" :as opcua]
            ["csv-parse/sync" :as csv]
            ["fs" :as fs]))

(defn read-sensors
  "Parses a csv file, returning a list of sensors group by machine"
  [path]
  (as-> path $
      (fs/readFileSync $)
      (csv/parse $ #js{:columns true :cast true})
      (->clj $)
      (group-by :machine $)))

(defn random-value
  "Returns a random Variant<Double> value between min and max"
  [min max]
  (fn []
    (let [value (rand-nth (range min max))]
      (opcua/Variant. (->js {:dataType (.. opcua -DataType -Double) :value value})))))

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
          (.addVariable namespace (->js {:componentOf             m
                                         :browseName              (:node sensor)
                                         :nodeId                  (str "s=" (:node sensor))
                                         :dataType                (:type sensor)
                                         :value                   {:get (random-value (:min sensor) (:max sensor))}
                                         :minimumSamplingInterval 1000})))))))

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
        sensors (read-sensors "./sensors.csv")]
    (try
      (p/do
        (js/console.log "#### Init server sensors...")
        (.initialize server)
        (populate-sensors server sensors)
        (js/console.log "#### Start server...")
        (.start server))
      (catch ExceptionInfo e
        (js/console.log "!!!! Error" e)))))
