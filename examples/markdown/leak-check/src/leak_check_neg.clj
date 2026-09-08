(ns leak-check-neg
  "Negative control: deliberately leaks opaque Document handles (no close!)
  to prove the detector actually fires on a real leak, not just silence.

  Loads the mem-trace build directly — see leak_check.clj's docstring.")

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

(defn leaky-round []
  (let [d (doc/parse sample {:tables true :strikethrough true ;; no with-opaque, no close! — leaks the Rust-side Box<Document>
                              :footnotes true :tasklists true})]
    (String. (doc/to-html d))))

(def iterations 20000)

(defn -main [& _]
  (println "=== markdown leak-check (negative control: intentional leak) ===")
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
