(ns leak-check
  "Loops every base64_capi bound fn many times and checks for leaked
  native allocations via AllocStats (stats_alloc wrapping the crate's
  global allocator — see base64_capi/src/lib.rs). Exact byte counts,
  no RSS polling, no GC-settle wait, no noise-floor thresholds: a
  leak-free run always nets to zero live bytes.

  Loads the mem-trace build directly (not dr/load!, which assumes the
  plain <lib>/target/release/... + lib<lib>_shim.dylib layout the
  consumable build uses) — see bind.clj's --out-suffix/CARGO_TARGET_DIR
  handling and rust-jolt-ef3.1 for why the traced build lives at a
  separate path instead of overwriting the consumable one.")

(require '[jolt.host :as host])
(require '[jolt.ffi :as ffi])
(require '[diplomat.runtime :as dr])

(def demo-dir (str (host/getenv "PWD") "/.."))
(ffi/load-library (str demo-dir "/base64_capi/target-traced/release/libbase64_capi.dylib"))
(ffi/load-library (str demo-dir "/libbase64_capi-traced_shim.dylib"))
(require '[diplomat.alloc-stats :as as])
(require '[diplomat.codec :as b64])
(require '[diplomat.hex :as hex])

(defn bytes [s] (.getBytes s "UTF-8"))

(defn live-bytes []
  (- (as/bytes-allocated) (as/bytes-deallocated)))

(defn round-standard-codec []
  (dr/with-opaque [codec (b64/standard)]
    (let [raw (bytes "Hello, World! this is a leak-check payload 0123456789")
          enc (String. (b64/encode codec raw))
          _dec (b64/decode codec enc)]
      nil)))

(defn round-url-safe-codec []
  (dr/with-opaque [codec (b64/url-safe)]
    (let [raw (bytes "data with /+= chars for url-safe leak check")
          enc (String. (b64/encode codec raw))
          _dec (b64/decode codec enc)]
      nil)))

(defn round-decode-error []
  (dr/with-opaque [codec (b64/standard)]
    (try
      (b64/decode codec "not!valid!base64!!!")
      (catch clojure.lang.ExceptionInfo _e nil))))

(defn round-hex []
  (let [raw (bytes "deadbeefdeadbeefdeadbeef")
        enc (String. (hex/encode raw))
        _dec (hex/decode enc)]
    nil))

(defn round-hex-error []
  (try
    (hex/decode "xyz")
    (catch clojure.lang.ExceptionInfo _e nil)))

(defn run-round []
  (round-standard-codec)
  (round-url-safe-codec)
  (round-decode-error)
  (round-hex)
  (round-hex-error))

(def iterations 20000)

(defn -main [& _]
  (println "=== base64 leak-check ===")
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
