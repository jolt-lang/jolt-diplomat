(ns leak-check
  "Loops every markdown_capi bound fn many times and checks for leaked
  native allocations via AllocStats (stats_alloc wrapping the crate's
  global allocator — see markdown_capi/src/lib.rs). Exact byte counts,
  no RSS polling, no GC-settle wait, no noise-floor thresholds: a
  leak-free run always nets to zero live bytes.

  Loads the mem-trace build directly (not dr/load!, which assumes the
  plain <lib>/target/release/... + lib<lib>_shim.dylib layout the
  consumable build uses) — see bind.clj's --out-suffix/CARGO_TARGET_DIR
  handling and rust-jolt-ef3.1 for why the traced build lives at a
  separate path instead of overwriting the consumable one.

  markdown_capi's only opaque is Document — no nested owned-opaque
  returns, no Result/error path at all (pulldown-cmark's parser never
  fails), so this loop just exercises parse under every options
  combination plus the empty-input edge case.")

(require '[jolt.host :as host])
(require '[jolt.ffi :as ffi])
(require '[diplomat.runtime :as dr])

(def demo-dir (str (host/getenv "PWD") "/.."))
(ffi/load-library (str demo-dir "/markdown_capi/target-traced/release/libmarkdown_capi.dylib"))
(ffi/load-library (str demo-dir "/libmarkdown_capi-traced_shim.dylib"))
(require '[diplomat.alloc-stats :as as])
(require '[diplomat.document :as doc])

(def sample "# Title\n\nSome *text* with a ~~strike~~ and a table:\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n## Second heading\n")

(defn live-bytes []
  (- (as/bytes-allocated) (as/bytes-deallocated)))

(defn round-plain []
  (dr/with-opaque [d (doc/parse sample {:tables false :strikethrough false
                                         :footnotes false :tasklists false})]
    (doc/heading-count d)
    (doc/source-len d)
    (String. (doc/to-html d))))

(defn round-extended []
  (dr/with-opaque [d (doc/parse sample {:tables true :strikethrough true
                                         :footnotes true :tasklists true})]
    (doc/heading-count d)
    (doc/source-len d)
    (String. (doc/to-html d))))

(defn round-empty []
  (dr/with-opaque [d (doc/parse "" {:tables false :strikethrough false
                                     :footnotes false :tasklists false})]
    (doc/heading-count d)
    (String. (doc/to-html d))))

(defn run-round []
  (round-plain)
  (round-extended)
  (round-empty))

(def iterations 20000)

(defn -main [& _]
  (println "=== markdown leak-check ===")
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
