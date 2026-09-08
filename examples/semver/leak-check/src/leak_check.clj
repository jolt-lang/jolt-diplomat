(ns leak-check
  "Loops every semver_capi bound fn many times and checks for leaked
  native allocations via AllocStats (stats_alloc wrapping the crate's
  global allocator — see semver_capi/src/lib.rs). Exact byte counts,
  no RSS polling, no GC-settle wait, no noise-floor thresholds: a
  leak-free run always nets to zero live bytes.

  Loads the mem-trace build directly (not dr/load!, which assumes the
  plain <lib>/target/release/... + lib<lib>_shim.dylib layout the
  consumable build uses) — see bind.clj's --out-suffix/CARGO_TARGET_DIR
  handling and rust-jolt-ef3.1 for why the traced build lives at a
  separate path instead of overwriting the consumable one.

  semver_capi has TWO independently-owned opaques (Version, VersionReq)
  used together in one call (VersionReq/matches borrows a &Version) —
  distinct from every other example so far, which had a single opaque
  or a nested owned-opaque return. Both need their own with-opaque;
  neither transfers ownership to the other.")

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

(defn round-version []
  (dr/with-opaque [ver (v/parse "1.2.3")]
    (v/major ver)
    (v/minor ver)
    (v/patch ver)
    (v/pre ver) ;; no pre-release -> nil, no leak either way
    (String. (v/to-string ver))))

(defn round-prerelease []
  (dr/with-opaque [ver (v/parse "2.0.0-alpha.1")]
    (v/is-prerelease ver)
    (some-> (v/pre ver) String.)))

(defn round-matches []
  (dr/with-opaque [req (vr/parse ">=1.0, <2.0")]
    (dr/with-opaque [v1 (v/parse "1.5.0")]
      (vr/matches req v1))
    (dr/with-opaque [v2 (v/parse "2.0.0")]
      (vr/matches req v2))
    (String. (vr/to-string req))))

(defn round-version-parse-error []
  (try
    (v/parse "not-a-version")
    (catch clojure.lang.ExceptionInfo _e nil)))

(defn round-version-req-parse-error []
  (try
    (vr/parse "not a req $$$")
    (catch clojure.lang.ExceptionInfo _e nil)))

(defn run-round []
  (round-version)
  (round-prerelease)
  (round-matches)
  (round-version-parse-error)
  (round-version-req-parse-error))

(def iterations 20000)

(defn -main [& _]
  (println "=== semver leak-check ===")
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
