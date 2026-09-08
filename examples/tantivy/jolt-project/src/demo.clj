(ns demo)

(require '[jolt.host :as host])
(require '[diplomat.runtime :as dr])

(def tantivy-dir (str (host/getenv "PWD") "/.."))
(dr/load! tantivy-dir "tantivy_capi")

(require '[diplomat.search-index :as idx])
(require '[diplomat.result-set   :as rs])

;; ── product corpus ────────────────────────────────────────────────────────────

(def PRODUCTS
  [;; Audio
   ["Sony WH-1000XM5"                "Industry-leading noise cancelling wireless headphones with 30hr battery"        "Audio"      34999]
   ["Bose QuietComfort 45"           "Wireless Bluetooth headphones with adaptive noise cancellation"                 "Audio"      27900]
   ["Apple AirPods Pro 2nd Gen"      "Active noise cancellation earbuds with transparency mode"                       "Audio"      24900]
   ["JBL Charge 5 Bluetooth Speaker" "Portable waterproof Bluetooth speaker with 20hr playtime"                       "Audio"       9999]
   ["JBL Clip 4"                     "Ultra-portable carabiner Bluetooth speaker, waterproof, 10hr battery"           "Audio"       4999]
   ["Sennheiser HD 650"              "Open-back audiophile headphones, natural sound, wired"                          "Audio"      39900]
   ["Sony WF-1000XM4"               "True wireless earbuds with industry-leading noise cancellation"                  "Audio"      19999]
   ;; Electronics
   ["Anker 737 Power Bank"           "High-capacity 140W portable charger, charges laptops and phones"                "Electronics" 8999]
   ["Raspberry Pi 5"                 "Single-board computer, 4GB RAM, runs Linux, perfect for makers"                 "Electronics" 6000]
   ["Logitech MX Master 3S"          "Advanced wireless mouse, quiet clicks, 8K DPI, works on any surface"            "Electronics" 9999]
   ["Keychron K2 Keyboard"           "Compact wireless mechanical keyboard, Mac and Windows"                          "Electronics" 8999]
   ["CalDigit TS4 Thunderbolt Dock"  "Thunderbolt 4 dock with 18 ports for Mac and PC"                               "Electronics" 34999]
   ;; Kitchen
   ["Instant Pot Duo 7-in-1"         "Electric pressure cooker, slow cooker, rice cooker — 6 quart"                   "Kitchen"     8999]
   ["Nespresso Vertuo Pop"           "Compact coffee and espresso machine with five cup sizes"                        "Kitchen"     6990]
   ["Breville Barista Express"       "Bean-to-cup espresso machine with integrated grinder"                           "Kitchen"    59999]
   ["Vitamix 5200 Blender"           "Professional-grade blender for smoothies, soups, and frozen desserts"           "Kitchen"    39900]
   ["OXO Good Grips Coffee Grinder"  "Conical burr grinder with 15 grind settings for any brew method"                "Kitchen"     4900]
   ;; Outdoors
   ["Garmin Fenix 7"                 "Rugged GPS smartwatch for hiking, running, and multisport"                       "Outdoors"  69999]
   ["Patagonia Nano Puff Jacket"     "Lightweight insulated jacket, packable, wind-resistant"                         "Outdoors"  19900]
   ["Hydro Flask 32oz"              "Vacuum insulated stainless steel water bottle, keeps cold 24hr"                   "Outdoors"   4499]])

(defn fmt-price [cents]
  (format "$%.2f" (/ cents 100.0)))

(defn print-results [results]
  (if (zero? (rs/count results))
    (println "  (no results)")
    (dotimes [i (rs/count results)]
      (printf "  %d. [%.2f] %-42s %s%n"
              (inc i)
              (rs/get-score results i)
              (String. (rs/get-title results i))
              (fmt-price (rs/get-price-cents results i)))
      (let [snip (String. (rs/get-snippet results i))]
        (when (seq snip)
          (printf "     %s%n" snip))))))

(defn run-query [index query limit]
  (println (str "\nSearch: \"" query "\""))
  (let [results (idx/search index query limit)]
    (print-results results)))

(defn assert-pass [label ok?]
  (println (str (if ok? "  PASS" "  FAIL") " " label))
  (when-not ok? (System/exit 1)))

(defn smoke-test [index]
  (println "\n── Smoke tests ──────────────────────────────────────────")
  (assert-pass "doc-count = 20"
    (= 20 (idx/doc-count index)))
  (let [r (idx/search index "wireless headphones" 5)]
    (assert-pass "wireless headphones returns results"
      (pos? (rs/count r)))
    (assert-pass "top wireless headphones result is headphone/wireless product"
      (let [t (.toLowerCase (String. (rs/get-title r 0)))
            d (.toLowerCase (String. (rs/get-description r 0)))]
        (or (.contains t "headphone") (.contains t "wireless")
            (.contains d "headphone") (.contains d "wireless")))))
  (let [r (idx/search index "coffee" 5)]
    (assert-pass "coffee returns results"
      (pos? (rs/count r))))
  (let [r (idx/search index "bluetooth speaker" 3)]
    (assert-pass "bluetooth speaker top result is a speaker"
      (.contains (.toLowerCase (String. (rs/get-title r 0))) "speaker")))
  (let [r (idx/search index "" 5)]
    (assert-pass "empty query returns 0 results"
      (zero? (rs/count r))))
  (let [r (idx/search index "headphones" 1)]
    (assert-pass "limit=1 returns exactly 1 result"
      (= 1 (rs/count r))))
  (println "\nAll smoke tests passed."))

(defn -main [& args]
  (println "=== tantivy product search via Diplomat + Jolt ===")
  (println (str "Indexing " (count PRODUCTS) " products..."))
  (dr/with-opaque [index (idx/new-in-ram)]
    (let [start (System/currentTimeMillis)]
      (doseq [[title desc cat price] PRODUCTS]
        (idx/add-product index title desc cat price))
      (idx/commit index)
      (printf "Indexed %d products in %dms%n%n"
              (idx/doc-count index)
              (- (System/currentTimeMillis) start)))

    (if (some #{"--test"} args)
      (smoke-test index)
      (do
        (println "── Canned queries ───────────────────────────────────────")
        (run-query index "wireless headphones"    5)
        (run-query index "coffee"                 5)
        (run-query index "portable waterproof"    5)
        (run-query index "bluetooth speaker"      3)
        (println "\n── Interactive search (empty line to quit) ──────────────")
        (loop []
          (print "\nSearch: ")
          (flush)
          (let [q (read-line)]
            (when (and q (seq q))
              (run-query index q 5)
              (recur))))))))
