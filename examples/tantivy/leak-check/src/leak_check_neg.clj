(ns leak-check-neg
  "Negative control: leaks the ResultSet returned by search (no close!) —
  the realistic mistake shape for this API, since search always returns
  an owned, non-null ResultSet (even for zero hits) and it's easy to read
  the hits and forget the set itself needs closing, same category of
  mistake as json's array_get.

  Loads the mem-trace build directly — see leak_check.clj's docstring.")

(require '[jolt.host :as host])
(require '[jolt.ffi :as ffi])
(require '[diplomat.runtime :as dr])

(def demo-dir (str (host/getenv "PWD") "/.."))
(ffi/load-library (str demo-dir "/tantivy_capi/target-traced/release/libtantivy_capi.dylib"))
(ffi/load-library (str demo-dir "/libtantivy_capi-traced_shim.dylib"))
(require '[diplomat.alloc-stats :as as])
(require '[diplomat.search-index :as idx])
(require '[diplomat.result-set :as rs])

(defn live-bytes []
  (- (as/bytes-allocated) (as/bytes-deallocated)))

(def products
  [["Sony WH-1000XM5" "Industry-leading noise cancelling wireless headphones" "Audio" 34999]
   ["Bose QuietComfort 45" "Wireless Bluetooth headphones with adaptive noise cancellation" "Audio" 27900]
   ["JBL Clip 4" "Ultra-portable carabiner Bluetooth speaker, waterproof" "Audio" 4999]])

(defn build-index []
  (let [index (idx/new-in-ram)]
    (doseq [[title desc cat price] products]
      (idx/add-product index title desc cat price))
    (idx/commit index)
    index))

(defn leaky-round [index]
  (let [r (idx/search index "headphones" 5)] ;; no with-opaque, no close! — leaks the Rust-side Box<ResultSet>
    (rs/count r)
    (when (pos? (rs/count r))
      (String. (rs/get-title r 0)))))

(def iterations 20000)

(defn -main [& _]
  (println "=== tantivy leak-check (negative control: intentional leak) ===")
  (let [index (build-index)]
    (dotimes [_ 500] (leaky-round index))
    (let [baseline (live-bytes)]
      (println "live bytes after warm-up:" baseline)
      (dotimes [_ iterations] (leaky-round index))
      (let [after (live-bytes)
            leaked (- after baseline)]
        (println "live bytes after" iterations "iterations:" after)
        (println "leaked:" leaked "bytes (" (format "%.3f" (double (/ leaked iterations))) "bytes/iteration )")
        (if (pos? leaked)
          (println "SUSPECT LEAK:" leaked "bytes never deallocated across" iterations "iterations")
          (println "no leak detected: every byte allocated was deallocated"))))))
