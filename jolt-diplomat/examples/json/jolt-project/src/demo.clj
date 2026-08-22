(ns demo)

(require '[jolt.ffi :as ffi])
(require '[jolt.host :as host])
(require '[diplomat.runtime :as dr])

(def demo-dir (str (host/getenv "PWD") "/.."))
(dr/load! demo-dir "json_capi")

(require '[diplomat.json-value :as jv])
(require '[diplomat.json-kind :as jk])

(defn -main [& _]
  (println "=== serde_json via Diplomat + Jolt ===")

  ;; scalar values
  (dr/with-opaque [v (jv/parse "42.5")]
    (println "kind:" (jv/kind v))
    (println "as-f64:" (jv/as-f64 v)))

  (dr/with-opaque [v (jv/parse "true")]
    (println "bool kind:" (jv/kind v))
    (println "as-bool:" (not= 0 (jv/as-bool v))))

  (dr/with-opaque [v (jv/parse "\"hello world\"")]
    (println "str kind:" (jv/kind v))
    (println "as-str:" (some-> (jv/as-str v) String.)))

  ;; array navigation
  (dr/with-opaque [v (jv/parse "[10, 20, 30]")]
    (println "array len:" (jv/array-len v))
    (dr/when-opaque [el (jv/array-get v 1)]
      (println "array[1]:" (jv/as-f64 el)))
    (println "array[99] nil?:" (dr/when-opaque [el (jv/array-get v 99)]
                                 (jv/as-f64 el))))

  ;; object access
  (dr/with-opaque [v (jv/parse "{\"name\":\"Alice\",\"age\":30}")]
    (println "kind:" (jv/kind v))
    (println "name:" (some-> (jv/object-get v "name") String.))
    (println "age:"  (some-> (jv/object-get v "age") String.))
    (println "missing key:" (jv/object-get v "missing")))

  ;; round-trip
  (dr/with-opaque [v (jv/parse "{\"x\":1,\"y\":[2,3]}")]
    (println "round-trip:" (String. (jv/to-string v))))

  ;; error path
  (try
    (jv/parse "{bad json}")
    (catch clojure.lang.ExceptionInfo e
      (println "parse error:" (ex-message e)))))
