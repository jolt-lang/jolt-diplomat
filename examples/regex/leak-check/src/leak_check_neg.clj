(ns leak-check-neg
  "Negative control: deliberately leaks opaque Regex handles (no close!) to
  prove the detector actually fires on a real leak, not just silence.

  Loads the mem-trace build directly — see leak_check.clj's docstring.")

(require '[jolt.host :as host])
(require '[jolt.ffi :as ffi])
(require '[diplomat.runtime :as dr])

(def demo-dir (str (host/getenv "PWD") "/.."))
(ffi/load-library (str demo-dir "/regex_capi/target-traced/release/libregex_capi.dylib"))
(ffi/load-library (str demo-dir "/libregex_capi-traced_shim.dylib"))
(require '[diplomat.alloc-stats :as as])
(require '[diplomat.regex :as rx])

(defn live-bytes []
  (- (as/bytes-allocated) (as/bytes-deallocated)))

(defn leaky-round []
  (let [re (rx/create "\\d+")] ;; no with-opaque, no close! — leaks the Rust-side Box<Regex>
    (rx/is-match re "42")))

(def iterations 20000)

(defn -main [& _]
  (println "=== regex leak-check (negative control: intentional leak) ===")
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
