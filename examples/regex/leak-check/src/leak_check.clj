(ns leak-check
  "Loops every regex_capi bound fn many times and checks for leaked
  native allocations via AllocStats (stats_alloc wrapping the crate's
  global allocator — see regex_capi/src/lib.rs). Exact byte counts,
  no RSS polling, no GC-settle wait, no noise-floor thresholds: a
  leak-free run always nets to zero live bytes.

  Loads the mem-trace build directly (not dr/load!, which assumes the
  plain <lib>/target/release/... + lib<lib>_shim.dylib layout the
  consumable build uses) — see bind.clj's --out-suffix/CARGO_TARGET_DIR
  handling and rust-jolt-ef3.1 for why the traced build lives at a
  separate path instead of overwriting the consumable one.

  regex_capi's only opaque is Regex — no nested owned-opaque returns,
  flat shape like base64/chrono. Covers the create error path and
  find's nullable (no-match) path.")

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

(defn round-match []
  (dr/with-opaque [re (rx/create "\\d+")]
    (rx/is-match re "42")
    (rx/is-match re "hello")
    (some-> (rx/find re "abc 123") String.)
    (String. (rx/replace-all re "a1 b2 c3" "NUM"))))

(defn round-no-match []
  (dr/with-opaque [re (rx/create "[A-Z]+")]
    (rx/find re "hello") ;; no match -> nil, no leak either way
    (some-> (rx/find re "HELLO") String.)))

(defn round-create-error []
  (try
    (rx/create "[invalid")
    (catch clojure.lang.ExceptionInfo _e nil)))

(defn run-round []
  (round-match)
  (round-no-match)
  (round-create-error))

(def iterations 20000)

(defn -main [& _]
  (println "=== regex leak-check ===")
  (println "iterations:" iterations)
  (dotimes [_ 500] (run-round)) ;; warm up: let one-time allocations (lazy statics, etc) settle
  (let [baseline (live-bytes)]
    (println "live bytes after warm-up:" baseline)
    (dotimes [_ iterations] (run-round))
    (let [after (live-bytes)
          leaked (- after baseline)]
      (println "live bytes after" iterations "iterations:" after)
      (println "leaked:" leaked "bytes (" (format "%.3f" (double (/ leaked iterations))) "bytes/iteration )")
      (if (pos? leaked)
        (println "SUSPECT LEAK:" leaked "bytes never deallocated across" iterations "iterations")
        (println "no leak detected: every byte allocated was deallocated")))))
