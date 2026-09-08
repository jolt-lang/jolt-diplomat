(ns leak-check
  "Loops every chrono_capi bound fn many times and checks for leaked
  native allocations via AllocStats (stats_alloc wrapping the crate's
  global allocator — see chrono_capi/src/lib.rs). Exact byte counts,
  no RSS polling, no GC-settle wait, no noise-floor thresholds: a
  leak-free run always nets to zero live bytes.

  Loads the mem-trace build directly (not dr/load!, which assumes the
  plain <lib>/target/release/... + lib<lib>_shim.dylib layout the
  consumable build uses) — see bind.clj's --out-suffix/CARGO_TARGET_DIR
  handling and rust-jolt-ef3.1 for why the traced build lives at a
  separate path instead of overwriting the consumable one.

  chrono_capi's only opaque is DateTime — no nested owned-opaque returns
  like json's array_get, so this is the flat leak shape (same as base64).")

(require '[jolt.host :as host])
(require '[jolt.ffi :as ffi])
(require '[diplomat.runtime :as dr])

(def demo-dir (str (host/getenv "PWD") "/.."))
(ffi/load-library (str demo-dir "/chrono_capi/target-traced/release/libchrono_capi.dylib"))
(ffi/load-library (str demo-dir "/libchrono_capi-traced_shim.dylib"))
(require '[diplomat.alloc-stats :as as])
(require '[diplomat.date-time :as dt])

(defn live-bytes []
  (- (as/bytes-allocated) (as/bytes-deallocated)))

(defn round-now []
  (dr/with-opaque [now (dt/now)]
    (String. (dt/to-rfc3339 now))
    (dt/timestamp-secs now)
    (dt/components now)))

(defn round-parse []
  (dr/with-opaque [ts (dt/parse "2024-03-15T12:34:56Z")]
    (String. (dt/to-rfc3339 ts))
    (dt/timestamp-secs ts)
    (dt/components ts)))

(defn round-format []
  (dr/with-opaque [ts (dt/parse "2024-03-15T12:34:56Z")]
    (some-> (dt/format ts "%Y-%m-%d") String.)
    (dt/format ts "%Q%Q%Q"))) ;; invalid format string -> nil, no leak either way

(defn round-from-timestamp []
  (dr/when-opaque [ts (dt/from-timestamp 0)]
    (String. (dt/to-rfc3339 ts)))
  (dt/from-timestamp -9999999999999)) ;; out-of-range -> nil, no leak either way

(defn round-parse-error []
  (try
    (dt/parse "not a date")
    (catch clojure.lang.ExceptionInfo _e nil)))

(defn run-round []
  (round-now)
  (round-parse)
  (round-format)
  (round-from-timestamp)
  (round-parse-error))

(def iterations 20000)

(defn -main [& _]
  (println "=== chrono leak-check ===")
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
