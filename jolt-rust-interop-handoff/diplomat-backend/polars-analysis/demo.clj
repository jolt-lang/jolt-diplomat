;; End-to-end demo for polars-bridge-stub — verified against:
;; diplomat 0.15, diplomat_core 0.15, Jolt 0.7.14
;; Expected output:
;;   :row-count 5
;;   :sum 15.0
;;   :map-reduce-threaded-result 55.0
;;   :expected 55.0

(require '[jolt.ffi :as ffi])
(ffi/load-library (System/getenv "POLARS_STUB_LIB"))
(require '[diplomat.runtime :as dr])

;; --- FFI declarations ---

(ffi/defcfn ^:private c-try-from-csv-sizeof
  "jolt_sizeof_try_from_csv_result" [] :int)

(ffi/defcfn ^:private c-try-from-csv
  "jolt_StubDataFrame_try_from_csv" [:string :size_t :pointer] :void)

(ffi/defcfn ^:private c-row-count
  "StubDataFrame_row_count" [:pointer] :size_t)

(ffi/defcfn ^:private c-sum
  "StubDataFrame_sum" [:pointer] :double)

(ffi/defcfn ^:private c-map-reduce-threaded
  "jolt_StubDataFrame_map_reduce_threaded" [:pointer :pointer :pointer :pointer] :double)

(ffi/defcfn ^:private c-destroy
  "StubDataFrame_destroy" [:pointer] :void)

;; --- Wrappers ---

(defn try-from-csv [csv]
  (let [sz  (c-try-from-csv-sizeof)
        out (ffi/alloc sz)]
    (try
      (c-try-from-csv csv (count csv) out)
      (dr/unwrap-result!
        (if (= 1 (ffi/read out :uint8 8))
          {:ok? true :value {:ptr (ffi/read out :pointer 0) :owned? true}}
          {:ok? false :error (ffi/read out :int 0)})
        "StubDataFrame/try-from-csv")
      (finally (ffi/free out)))))

(defn row-count [df] (c-row-count (:ptr df)))
(defn sum      [df] (c-sum (:ptr df)))

(defn map-reduce-threaded [df f]
  (let [state      (atom nil)
        run-cb     (ffi/foreign-callable
                     (fn [_data x] (f x))
                     [:pointer :double] :double :collect-safe)
        destructor (ffi/foreign-callable
                     (fn [_data]
                       (let [{:keys [run-cb destructor]} @state]
                         (ffi/free-callable run-cb)
                         (ffi/free-callable destructor)))
                     [:pointer] :void :collect-safe)]
    (reset! state {:run-cb run-cb :destructor destructor})
    (c-map-reduce-threaded (:ptr df) ffi/null run-cb destructor)))

;; --- Demo ---

(let [df (try-from-csv "1.0, 2.0, 3.0, 4.0, 5.0")]
  (println :row-count (row-count df))
  (println :sum (sum df))
  (let [result   (map-reduce-threaded df (fn [x] (* x x)))
        expected (apply + (map #(* % %) [1.0 2.0 3.0 4.0 5.0]))]
    (println :map-reduce-threaded-result result)
    (println :expected expected))
  (c-destroy (:ptr df)))
