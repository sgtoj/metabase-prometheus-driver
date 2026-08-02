(ns metabase.driver.prometheus
  (:require
   [clojure.core.async :as a]
   [clojure.string :as str]
   [metabase.driver :as driver]
   [metabase.driver-api.core :as driver-api]
   [metabase.driver.connection :as driver.connection]
   [metabase.util.log :as log])
  (:import
   (io.cruxstack.metabase.prometheus ColumnType DriverQueryException PrometheusDriver)))

(driver/register! :prometheus)

(defn- detail
  [details key]
  (or (get details key) (get details (name key))))

(defn- resolved-details
  [details]
  (case (some-> (detail details :auth-mode) name str/lower-case)
    "basic" (assoc details :password
                    (driver-api/secret-value-as-string :prometheus details "password"))
    "bearer" (assoc details :bearer-token
                     (driver-api/secret-value-as-string :prometheus details "bearer-token"))
    details))

(defmethod driver/database-supports? [:prometheus :native-parameters]
  [_driver _feature _database]
  true)

(defmethod driver/database-supports? [:prometheus :schemas]
  [_driver _feature _database]
  false)

(defmethod driver/database-supports? [:prometheus :basic-aggregations]
  [_driver _feature _database]
  false)

(defmethod driver/database-supports? [:prometheus :case-sensitivity-string-filter-options]
  [_driver _feature _database]
  false)

(defmethod driver/database-supports? [:prometheus :date-arithmetics]
  [_driver _feature _database]
  false)

(defmethod driver/database-supports? [:prometheus :temporal-extract]
  [_driver _feature _database]
  false)

(defmethod driver/database-supports? [:prometheus :fingerprint]
  [_driver _feature _database]
  false)

(defmethod driver/database-supports? [:prometheus :upload-with-auto-pk]
  [_driver _feature _database]
  false)

(defmethod driver/database-supports? [:prometheus :saved-question-sandboxing]
  [_driver _feature _database]
  false)

(defmethod driver/database-supports? [:prometheus :test/create-table-without-data]
  [_driver _feature _database]
  false)

(defmethod driver/database-supports? [:prometheus :test/dynamic-dataset-loading]
  [_driver _feature _database]
  false)

(defmethod driver/database-supports? [:prometheus :test/jvm-timezone-setting]
  [_driver _feature _database]
  false)

(defmethod driver/database-supports? [:prometheus :test/uuids-in-create-table-statements]
  [_driver _feature _database]
  false)

(defmethod driver/can-connect? :prometheus
  [_driver details]
  (PrometheusDriver/canConnect (resolved-details details)))

;; Metabase 0.57+ validates details independently of the network connection test.
(when-let [method-var (ns-resolve 'metabase.driver 'validate-db-details!)]
  (let [^clojure.lang.MultiFn method (var-get method-var)]
    (.addMethod method :prometheus
                (fn [_driver details]
                  (PrometheusDriver/validateConfig (resolved-details details))
                  nil))))

(defmethod driver/dbms-version :prometheus
  [_driver database]
  (let [version (PrometheusDriver/dbmsVersion
                 (resolved-details (driver.connection/effective-details database)))]
    {:flavor  (.getFlavor version)
     :version (.getVersion version)}))

(defmethod driver/describe-database* :prometheus
  [_driver _database]
  {:tables #{{:name "query_context" :schema nil}}})

(defmethod driver/describe-table :prometheus
  [_driver _database table]
  (if (= "query_context" (:name table))
    {:fields #{{:name "timestamp"
                :database-type "timestamp"
                :base-type :type/DateTime
                :database-position 0}}}
    {:fields #{}}))

(defmethod driver/mbql->native :prometheus
  [_driver _query]
  (throw (ex-info "Mimir / Prometheus supports native PromQL queries only"
                  {:driver :prometheus
                   :type driver-api/qp.error-type.unsupported-feature})))

(defn- substitute-native-query
  [native-query]
  (PrometheusDriver/substituteNativeQuery
   native-query
   nil
   nil
   (or (driver-api/requested-timezone-id) "UTC")))

(defn- substitute-form
  [form text-key]
  (try
    (assoc form text-key
           (PrometheusDriver/substituteNativeQuery
            (get form text-key)
            (:template-tags form)
            (:parameters form)
            (or (driver-api/requested-timezone-id) "UTC")))
    (catch IllegalArgumentException exception
      (let [message (.getMessage exception)
            missing? (or (str/includes? message "required")
                         (str/includes? message "No value was supplied"))]
        (throw (ex-info message
                        {:driver :prometheus
                         :type (if missing?
                                 driver-api/qp.error-type.missing-required-parameter
                                 driver-api/qp.error-type.invalid-parameter)}
                        exception))))))

;; Metabase 0.60 invokes this legacy method.
(defmethod driver/substitute-native-parameters :prometheus
  [_driver inner-query]
  (substitute-form inner-query :query))

;; Metabase 0.63 moved native parameter substitution to an MBQL stage method.
;; Register dynamically so this namespace still loads on Metabase 0.60.
(when-let [method-var (ns-resolve 'metabase.driver 'substitute-native-parameters-in-stage-method)]
  (let [^clojure.lang.MultiFn method (var-get method-var)]
    (.addMethod method :prometheus
                (fn [_driver _metadata-providerable stage]
                  (substitute-form stage :native)))))

(defn- column-metadata
  [column]
  (let [[base-type database-type]
        (condp = (.getType column)
          ColumnType/TEMPORAL [:type/DateTime "timestamp"]
          ColumnType/FLOAT    [:type/Float "double"]
          ColumnType/TEXT     [:type/Text "text"])]
    {:name          (.getName column)
     :display_name  (.getName column)
     :base_type     base-type
     :database_type database-type}))

(defn- error-type
  [^DriverQueryException exception]
  (case (.. exception getCategory name)
    "VALIDATION" driver-api/qp.error-type.invalid-query
    "GUARDRAIL" driver-api/qp.error-type.invalid-query
    "TIMEOUT" :timed-out
    "CANCELED" driver-api/qp.error-type.qp
    "CONNECTION" driver-api/qp.error-type.db
    "HTTP" driver-api/qp.error-type.db
    "BACKEND" driver-api/qp.error-type.db
    driver-api/qp.error-type.driver))

(defn- driver-error
  [^DriverQueryException exception]
  (let [category (.. exception getCategory name)]
    (ex-info (.getMessage exception)
             (cond-> {:driver   :prometheus
                      :category category
                      :type     (error-type exception)}
               (= category "CANCELED")
               (assoc :query/query-canceled? true))
             exception)))

(defn- result-rows
  [rows canceled-chan]
  (let [iterator (.iterator rows)]
    (driver-api/reducible-rows
     (fn []
       (when (.hasNext iterator)
         (vec (.next iterator))))
     canceled-chan)))

(defmethod driver/execute-reducible-query :prometheus
  [_driver query context respond]
  (let [native-query (get-in query [:native :query])]
    (when-not (string? native-query)
      (throw (ex-info "Mimir / Prometheus supports native PromQL queries only"
                      {:driver :prometheus
                       :type driver-api/qp.error-type.unsupported-feature})))
    (try
      (let [database      (driver-api/database (driver-api/metadata-provider))
            details       (resolved-details (driver.connection/effective-details database))
            execution     (PrometheusDriver/startQuery details (substitute-native-query native-query))
            canceled-chan (or (:canceled-chan context) (driver-api/canceled-chan))
            started-nanos (System/nanoTime)]
        (when canceled-chan
          (a/go
            (when (a/<! canceled-chan)
              (.cancel execution))))
        (let [result   (.await execution)
              metadata (cond->
                         {:cols (mapv column-metadata (.getColumns result))}
                         (seq (.getWarnings result))
                         (assoc :warnings (vec (.getWarnings result))))]
          (log/debugf
           "Mimir query mode=%s query_hash=%s elapsed_ms=%d series_count=%d row_count=%d response_bytes=%d"
           (.getMode execution)
           (.getQueryHash execution)
           (long (/ (- (System/nanoTime) started-nanos) 1000000))
           (.getSeriesCount result)
           (count (.getRows result))
           (.getResponseBytes result))
          (respond metadata (result-rows (.getRows result) canceled-chan))))
      (catch DriverQueryException exception
        (log/warnf "Mimir query failed query_hash=%s category=%s"
                   (PrometheusDriver/queryHash native-query)
                   (.. exception getCategory name))
        (throw (driver-error exception)))
      (catch IllegalArgumentException exception
        (throw (ex-info (.getMessage exception)
                        {:driver :prometheus
                         :type driver-api/qp.error-type.invalid-query}
                        exception))))))
