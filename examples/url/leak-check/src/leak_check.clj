(ns leak-check
  "Loops every url_capi bound fn many times and checks for leaked
  native allocations via AllocStats (stats_alloc wrapping the crate's
  global allocator — see url_capi/src/lib.rs). Exact byte counts,
  no RSS polling, no GC-settle wait, no noise-floor thresholds: a
  leak-free run always nets to zero live bytes.

  Loads the mem-trace build directly (not dr/load!, which assumes the
  plain <lib>/target/release/... + lib<lib>_shim.dylib layout the
  consumable build uses) — see bind.clj's --out-suffix/CARGO_TARGET_DIR
  handling and rust-jolt-ef3.1 for why the traced build lives at a
  separate path instead of overwriting the consumable one.

  url_capi's only opaque is Url — no nested owned-opaque returns, flat
  shape like base64/chrono/regex. Covers the parse error path and the
  nullable host/query/port paths (with and without those components
  present), plus the struct-by-value info() accessor.")

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

(defn round-full []
  (dr/with-opaque [url (u/parse "https://user@example.com:8080/path/to/page?foo=bar&baz=qux")]
    (String. (u/scheme url))
    (some-> (u/host url) String.)
    (String. (u/path url))
    (some-> (u/query url) String.)
    (u/port url)
    (String. (u/to-string url))
    (u/info url)))

(defn round-no-query-no-port []
  (dr/with-opaque [url (u/parse "https://example.com/no-query")]
    (u/query url) ;; nil -> no leak either way
    (u/port url))) ;; nil -> no leak either way

(defn round-no-host []
  (dr/with-opaque [url (u/parse "data:text/plain,hello")]
    (u/host url) ;; nil -> no leak either way
    (String. (u/scheme url))))

(defn round-parse-error []
  (try
    (u/parse "not a url at all %%%")
    (catch clojure.lang.ExceptionInfo _e nil)))

(defn run-round []
  (round-full)
  (round-no-query-no-port)
  (round-no-host)
  (round-parse-error))

(def iterations 20000)

(defn -main [& _]
  (println "=== url leak-check ===")
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
