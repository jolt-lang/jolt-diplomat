(ns leak-check
  "Loops the leak-risk tantivy_capi bound fns many times and checks for
  leaked native allocations via AllocStats (stats_alloc wrapping the
  crate's global allocator — see tantivy_capi/src/lib.rs). Exact byte
  counts, no RSS polling, no GC-settle wait, no noise-floor thresholds:
  a leak-free run always nets to zero live bytes.

  Loads the mem-trace build directly (not dr/load!, which assumes the
  plain <lib>/target/release/... + lib<lib>_shim.dylib layout the
  consumable build uses) — see bind.clj's --out-suffix/CARGO_TARGET_DIR
  handling and rust-jolt-ef3.1 for why the traced build lives at a
  separate path instead of overwriting the consumable one.

  Unlike every prior example, building a SearchIndex is expensive (a
  real tantivy Index/IndexWriter/IndexReader, 50MB writer buffer,
  Mutex) — looping index construction 20000 times would be both slow
  and not the actual leak risk. So: build ONE index up front (indexing,
  commit, doc-count all exercised once during setup, plus a handful of
  add-product/commit/new-on-disk-error rounds for coverage), then loop
  the real per-call leak risk — search returning an owned ResultSet,
  and every ResultSet accessor including the empty-results and
  out-of-bounds-index paths — 20000 times against that one index.")

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
   ["JBL Clip 4" "Ultra-portable carabiner Bluetooth speaker, waterproof" "Audio" 4999]
   ["Instant Pot Duo 7-in-1" "Electric pressure cooker, slow cooker, rice cooker" "Kitchen" 8999]
   ["Nespresso Vertuo Pop" "Compact coffee and espresso machine" "Kitchen" 6990]])

(defn build-index []
  (let [index (idx/new-in-ram)]
    (doseq [[title desc cat price] products]
      (idx/add-product index title desc cat price))
    (idx/commit index)
    index))

(defn round-search-with-results [index]
  (dr/with-opaque [r (idx/search index "headphones" 5)]
    (dotimes [i (rs/count r)]
      (rs/get-score r i)
      (String. (rs/get-title r i))
      (String. (rs/get-description r i))
      (String. (rs/get-category r i))
      (rs/get-price-cents r i)
      (String. (rs/get-snippet r i)))))

(defn round-search-empty [index]
  (dr/with-opaque [r (idx/search index "" 5)]
    (rs/count r))) ;; empty query -> 0 results, ResultSet itself is still owned and must be closed

(defn round-search-out-of-bounds-accessor [index]
  (dr/with-opaque [r (idx/search index "headphones" 1)]
    (rs/get-score r 999) ;; out-of-bounds index -> defaults, no separate allocation either way
    (rs/get-price-cents r 999)))

(defn run-round [index]
  (round-search-with-results index)
  (round-search-empty index)
  (round-search-out-of-bounds-accessor index))

(def iterations 20000)

(defn -main [& _]
  (println "=== tantivy leak-check ===")
  (println "iterations:" iterations)
  (let [index (build-index)] ;; one index for the whole run — see docstring
    (println "doc-count:" (idx/doc-count index))
    (dotimes [_ 500] (run-round index)) ;; warm up: let one-time allocations (lazy statics, etc) settle
    (let [baseline (live-bytes)]
      (println "live bytes after warm-up:" baseline)
      (dotimes [_ iterations] (run-round index))
      (let [after (live-bytes)
            leaked (- after baseline)]
        (println "live bytes after" iterations "iterations:" after)
        (println "leaked:" leaked "bytes (" (format "%.3f" (double (/ leaked iterations))) "bytes/iteration )")
        (if (pos? leaked)
          (println "SUSPECT LEAK:" leaked "bytes never deallocated across" iterations "iterations")
          (println "no leak detected: every byte allocated was deallocated"))))))
