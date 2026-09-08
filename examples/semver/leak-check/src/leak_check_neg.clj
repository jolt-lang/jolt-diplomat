(ns leak-check-neg
  "Negative control: properly closes VersionReq but leaks the Version
  passed into matches — the realistic mistake shape for this API, since
  it's easy to remember to close! the 'main' object (the VersionReq
  you're driving the call from) and forget the second, borrowed-looking
  argument that's actually its own independently-owned opaque.

  Loads the mem-trace build directly — see leak_check.clj's docstring.")

(require '[jolt.host :as host])
(require '[jolt.ffi :as ffi])
(require '[diplomat.runtime :as dr])

(def demo-dir (str (host/getenv "PWD") "/.."))
(ffi/load-library (str demo-dir "/semver_capi/target-traced/release/libsemver_capi.dylib"))
(ffi/load-library (str demo-dir "/libsemver_capi-traced_shim.dylib"))
(require '[diplomat.alloc-stats :as as])
(require '[diplomat.version :as v])
(require '[diplomat.version-req :as vr])

(defn live-bytes []
  (- (as/bytes-allocated) (as/bytes-deallocated)))

(defn leaky-round []
  (dr/with-opaque [req (vr/parse ">=1.0, <2.0")]
    (let [ver (v/parse "1.5.0")] ;; no with-opaque, no close! — leaks the Rust-side Box<Version>
      (vr/matches req ver))))

(def iterations 20000)

(defn -main [& _]
  (println "=== semver leak-check (negative control: intentional leak) ===")
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
