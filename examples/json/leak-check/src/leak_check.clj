(ns leak-check
  "Loops every json_capi bound fn many times and checks for leaked native
  allocations via AllocStats (stats_alloc wrapping the crate's global
  allocator — see json_capi/src/lib.rs). Exact byte counts, no RSS
  polling. json_capi's array-get returns an OWNED nested opaque distinct
  from base64's flat opaque shape — exercised here to catch that
  lifetime too.

  Loads the mem-trace build directly (not dr/load!, which assumes the
  plain <lib>/target/release/... + lib<lib>_shim.dylib layout the
  consumable build uses) — see bind.clj's --out-suffix/CARGO_TARGET_DIR
  handling and rust-jolt-ef3.1 for why the traced build lives at a
  separate path instead of overwriting the consumable one.")

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

(defn round-scalar []
  (dr/with-opaque [v (jv/parse "42.5")]
    (jv/kind v)
    (jv/as-f64 v)))

(defn round-bool []
  (dr/with-opaque [v (jv/parse "true")]
    (jv/kind v)
    (jv/as-bool v)))

(defn round-str []
  (dr/with-opaque [v (jv/parse "\"hello world\"")]
    (jv/kind v)
    (some-> (jv/as-str v) String.)))

(defn round-array []
  (dr/with-opaque [v (jv/parse "[10, 20, 30]")]
    (jv/array-len v)
    (dr/when-opaque [el (jv/array-get v 1)] ;; owned nested opaque — must close!
      (jv/as-f64 el))
    (dr/when-opaque [el (jv/array-get v 99)] ;; out-of-bounds -> nil, no leak either way
      (jv/as-f64 el))))

(defn round-object []
  (dr/with-opaque [v (jv/parse "{\"name\":\"Alice\",\"age\":30}")]
    (some-> (jv/object-get v "name") String.)
    (some-> (jv/object-get v "age") String.)
    (jv/object-get v "missing")))

(defn round-to-string []
  (dr/with-opaque [v (jv/parse "{\"x\":1,\"y\":[2,3]}")]
    (String. (jv/to-string v))))

(defn round-parse-error []
  (try
    (jv/parse "{bad json}")
    (catch clojure.lang.ExceptionInfo _e nil)))

(defn round-builder []
  (dr/with-opaque [event (jv/parse "{\"k\":\"v\"}")]
    (dr/with-opaque [resp (jv/new-object)]
      (jv/set-string resp "message" "hello")
      (jv/set-number resp "n" 3.0)
      (jv/set-bool resp "ok" 1)
      (jv/set-value resp "event" event)
      (dr/with-opaque [tags (jv/new-array)]
        (dr/with-opaque [a (jv/new-string "a")]
          (jv/push tags a))
        (jv/set-value resp "tags" tags))
      (String. (jv/to-string resp)))))

(defn run-round []
  (round-scalar)
  (round-bool)
  (round-str)
  (round-array)
  (round-object)
  (round-to-string)
  (round-parse-error)
  (round-builder))

(def iterations 20000)

(defn -main [& _]
  (println "=== json leak-check ===")
  (println "iterations:" iterations)
  (dotimes [_ 500] (run-round))
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
