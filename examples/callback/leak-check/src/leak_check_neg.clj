(ns leak-check-neg
  "Negative control: calls Reducer/apply_twice_leaky, a test fixture added
  to callback_capi solely for this check (NOT part of the real example
  API — reduce/apply_twice run their closure inline and never retain it,
  so this crate has no real caller-controlled leak vector the way
  with-opaque/close! provides elsewhere; see leak_check.clj's docstring).
  apply_twice_leaky Box::leaks the boxed `impl Fn` trait object Diplomat
  builds to carry the closure across the FFI boundary — the Rust-side
  half of what a broken destructor invocation would produce.

  Loads the mem-trace build directly — see leak_check.clj's docstring.")

(require '[jolt.host :as host])
(require '[jolt.ffi :as ffi])
(require '[diplomat.runtime :as dr])

(def demo-dir (str (host/getenv "PWD") "/.."))
(ffi/load-library (str demo-dir "/callback_capi/target-traced/release/libcallback_capi.dylib"))
(ffi/load-library (str demo-dir "/libcallback_capi-traced_shim.dylib"))
(require '[diplomat.alloc-stats :as as])
(require '[diplomat.reducer :as r])

(defn live-bytes []
  (- (as/bytes-allocated) (as/bytes-deallocated)))

(defn leaky-round [i]
  (r/apply-twice-leaky i inc))

(def iterations 20000)

(defn -main [& _]
  (println "=== callback leak-check (negative control: intentional leak) ===")
  (dotimes [i 500] (leaky-round i))
  (let [baseline (live-bytes)]
    (println "live bytes after warm-up:" baseline)
    (dotimes [i iterations] (leaky-round i))
    (let [after (live-bytes)
          leaked (- after baseline)]
      (println "live bytes after" iterations "iterations:" after)
      (println "leaked:" leaked "bytes (" (format "%.3f" (double (/ leaked iterations))) "bytes/iteration )")
      (if (pos? leaked)
        (println "SUSPECT LEAK:" leaked "bytes never deallocated across" iterations "iterations")
        (println "no leak detected: every byte allocated was deallocated")))))
