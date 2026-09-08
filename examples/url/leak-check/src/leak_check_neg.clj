(ns leak-check-neg
  "Negative control: deliberately leaks opaque Url handles (no close!) to
  prove the detector actually fires on a real leak, not just silence.

  Loads the mem-trace build directly — see leak_check.clj's docstring.")

(require '[jolt.host :as host])
(require '[jolt.ffi :as ffi])
(require '[diplomat.runtime :as dr])

(def demo-dir (str (host/getenv "PWD") "/.."))
(ffi/load-library (str demo-dir "/url_capi/target-traced/release/liburl_capi.dylib"))
(ffi/load-library (str demo-dir "/liburl_capi-traced_shim.dylib"))
(require '[diplomat.alloc-stats :as as])
(require '[diplomat.url :as u])

(defn live-bytes []
  (- (as/bytes-allocated) (as/bytes-deallocated)))

(defn leaky-round []
  (let [url (u/parse "https://user@example.com:8080/path/to/page?foo=bar&baz=qux")] ;; no with-opaque, no close! — leaks the Rust-side Box<Url>
    (String. (u/scheme url))))

(def iterations 20000)

(defn -main [& _]
  (println "=== url leak-check (negative control: intentional leak) ===")
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
