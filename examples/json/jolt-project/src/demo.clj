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
      (println "parse error:" (ex-message e))))

  ;; builder API (added for lambda-mvp-rst: build a response object from
  ;; scratch, embed a parsed value, serialize)
  (dr/with-opaque [event (jv/parse "{\"key1\":\"value1\",\"key2\":\"value2\"}")]
    (dr/with-opaque [resp (jv/new-object)]
      (jv/set-string resp "message" "hello from jolt-diplomat")
      (jv/set-number resp "warm_invocation" 3.0)
      (jv/set-bool resp "ok" 1)
      (jv/set-value resp "event" event)
      (dr/with-opaque [tags (jv/new-array)]
        (dr/with-opaque [a (jv/new-string "a")]
          (jv/push tags a))
        (dr/with-opaque [b (jv/new-string "b")]
          (jv/push tags b))
        (jv/set-value resp "tags" tags))
      (println "built object:" (String. (jv/to-string resp))))))
