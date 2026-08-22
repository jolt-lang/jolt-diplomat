(ns demo)

(require '[jolt.ffi :as ffi])
(require '[jolt.host :as host])
(require '[diplomat.runtime :as dr])

(def demo-dir (str (host/getenv "PWD") "/.."))
(dr/load! demo-dir "chrono_capi")

(require '[diplomat.date-time :as dt])

(defn -main [& _]
  (println "=== chrono via Diplomat + Jolt ===")

  ;; now
  (dr/with-opaque [now (dt/now)]
    (println "now (rfc3339):" (String. (dt/to-rfc3339 now)))
    (println "timestamp-secs > 0?:" (> (dt/timestamp-secs now) 0))
    (let [c (dt/components now)]
      (println "components:" c)))

  ;; parse a known timestamp
  (dr/with-opaque [ts (dt/parse "2024-03-15T12:34:56Z")]
    (println "parsed rfc3339:" (String. (dt/to-rfc3339 ts)))
    (println "timestamp-secs:" (dt/timestamp-secs ts))
    (let [c (dt/components ts)]
      (println "year:" (:year c) "month:" (:month c) "day:" (:day c))
      (println "hour:" (:hour c) "minute:" (:minute c) "second:" (:second c))))

  ;; format
  (dr/with-opaque [ts (dt/parse "2024-03-15T12:34:56Z")]
    (println "formatted:" (some-> (dt/format ts "%Y-%m-%d") String.))
    (println "invalid fmt:" (dt/format ts "%Q%Q%Q")))

  ;; from-timestamp (nullable)
  (dr/when-opaque [ts (dt/from-timestamp 0)]
    (println "epoch:" (String. (dt/to-rfc3339 ts))))
  (println "invalid timestamp nil?:" (dt/from-timestamp -9999999999999))

  ;; parse error
  (try
    (dt/parse "not a date")
    (catch clojure.lang.ExceptionInfo e
      (println "parse error:" (ex-message e)))))
