(ns leak-check-neg
  "Negative control: leaks the nested array-get opaque (forgets when-opaque)
  while still properly closing the parent — the realistic mistake shape for
  json_capi, distinct from base64's flat forgot-to-close-anything case.

  Loads the mem-trace build directly — see leak_check.clj's docstring.")

(require '[jolt.host :as host])
(require '[jolt.ffi :as ffi])
(require '[diplomat.runtime :as dr])

(def demo-dir (str (host/getenv "PWD") "/.."))
(ffi/load-library (str demo-dir "/json_capi/target-traced/release/libjson_capi.dylib"))
(ffi/load-library (str demo-dir "/libjson_capi-traced_shim.dylib"))
(require '[diplomat.alloc-stats :as as])
(require '[diplomat.json-value :as jv])

(defn live-bytes []
  (- (as/bytes-allocated) (as/bytes-deallocated)))

(defn leaky-round []
  (dr/with-opaque [v (jv/parse "[10, 20, 30]")]
    (let [el (jv/array-get v 1)] ;; owned opaque, no when-opaque/close! — leaks
      (jv/as-f64 el))))

(def iterations 20000)

(defn -main [& _]
  (println "=== json leak-check (negative control: leaked nested opaque) ===")
  (dotimes [_ 500] (leaky-round))
  (let [baseline (live-bytes)]
    (println "live bytes after warm-up:" baseline)
    (dotimes [_ iterations] (leaky-round))
    (let [after (live-bytes)
          leaked (- after baseline)]
      (println "live bytes after" iterations "iterations:" after)
      (println "leaked:" leaked "bytes (" (format "%.3f" (double (/ leaked iterations))) "bytes/iteration )")
      (if (pos? leaked)
        (println "SUSPECT LEAK:" leaked "bytes never deallocated across" iterations "iterations")
        (println "no leak detected: every byte allocated was deallocated")))))
