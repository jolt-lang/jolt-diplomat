(ns diplomat.thingy
  "TARGET for `diplomat-tool --jolt` codegen — this version is the FINAL,
  doc-confirmed, empirically-verified-against-real-jolt-v0.7.13 shape.
  Supersedes the pre-Milestone-1 version. Every line here has actually run.
  See findings/milestone-4-findings.md for the native-interop.html
  cross-check that confirmed this design against Jolt's own docs."
  (:require [jolt.ffi :as ffi]
            [diplomat.runtime :as dr]))

;; --- opaque type + lifecycle --------------------------------------------

(dr/defopaque Thingy "Thingy_destroy")

;; --- fallible constructor -------------------------------------------------
;; Real generated C: Thingy_try_create_result Thingy_try_create(DiplomatStringView s)
;; — a by-value struct return AND a by-value struct param. jolt.ffi has no
;; :struct type (confirmed against native-interop.html's full type table —
;; :int :uint :long :ulong :int64 :uint64 :size_t :ssize_t :iptr :uptr
;; :double :float :char :uint8 :pointer :string :void, nothing else exists)
;; so BOTH crossings route through a generated shim that decomposes to
;; scalars, matching the doc's own "Out-parameters" pattern exactly
;; (alloc a cell, pass its address, read it back — the sqlite3_open
;; example) applied to Result instead of a bare pointer.

(ffi/defcfn ^:private c-try-create
  "jolt_Thingy_try_create" [:string :size_t :pointer] :void)

(ffi/defcfn ^:private c-sizeof-try-create-result
  "jolt_sizeof_try_create_result" [] :int)

(defn try-create
  "Thingy/try_create(&str) -> Result<Box<Thingy>, ThingyError>"
  [s]
  (let [sz  (c-sizeof-try-create-result)
        out (ffi/alloc sz)]
    (try
      (c-try-create s (count s) out)
      (dr/unwrap-result!
       (if (= 1 (ffi/read out :uint8 8)) ;; is_ok at offset 8 (union at 0, bool at 8)
         {:ok? true :value (->Thingy (ffi/read out :pointer 0) false)}
         {:ok? false :error (ffi/read out :int 0)}) ;; err shares the union slot
       "Thingy/try-create")
      (finally (ffi/free out)))))

;; --- plain scalar method --------------------------------------------------

(ffi/defcfn ^:private c-value "Thingy_value" [:pointer] :uint8)

(defn value [^Thingy this]
  (c-value (:ptr this)))

;; --- struct-by-value param + writeable string return ----------------------
;; ThingyOptions {bool verbose; double scale} decomposed to scalars in the
;; shim, same reasoning as try-create. `verbose` maps to :int, not a
;; dedicated :bool — jolt.ffi's type table has no :bool keyword at all;
;; :int works because x86-64 SysV passes sub-register integer types
;; (including _Bool) in the low bits of a full register regardless of the
;; callee's declared width. Verified working, not separately documented —
;; worth a comment at every callsite that does this, since it's a real but
;; undocumented convention.

(ffi/defcfn ^:private c-describe
  "jolt_Thingy_describe" [:pointer :int :double :pointer] :void)

(ffi/defcfn ^:private c-simple-write
  "jolt_diplomat_simple_write" [:pointer :size_t :pointer] :void)
  ;; diplomat_simple_write ALSO returns DiplomatWrite by value — routed
  ;; through the same shim pattern. Confirmed the hard way: declaring it
  ;; directly as returning :pointer from Jolt does not error, it silently
  ;; reads garbage (SysV ABI hidden out-pointer for >16-byte struct
  ;; returns). NEVER defcfn a struct-returning C symbol directly.

(defn describe
  "Thingy::describe(&self, ThingyOptions, &mut DiplomatWrite)"
  [^Thingy this {:keys [verbose scale]}]
  (let [buf (ffi/alloc 256)
        w   (ffi/alloc dr/writeable-struct-size)]
    (try
      (c-simple-write buf 256 w) ;; NEVER hand-assemble this struct — a
                                  ;; hand-built version with null flush/grow
                                  ;; crashes ("invalid memory reference"),
                                  ;; verified directly; Diplomat's writer
                                  ;; calls flush unconditionally to finalize.
      (c-describe (:ptr this) (if verbose 1 0) (double scale) w)
      (let [n (ffi/read w :size_t dr/O-len)]
        (ffi/read-bytes buf n))
      (finally (ffi/free buf) (ffi/free w)))))

;; --- slice param, copied not borrowed --------------------------------------

(ffi/defcfn ^:private c-sum-with "Thingy_sum_with" [:pointer :pointer :size_t] :uint)

(defn sum-with
  "Thingy::sum_with(&self, &[u8]) -> u32. `others` is COPIED across the
  boundary by Diplomat's own model, not borrowed."
  [^Thingy this others]
  (let [n   (count others)
        buf (ffi/alloc n)]
    (try
      (dotimes [i n] (ffi/write buf :uint8 i (nth others i)))
      (c-sum-with (:ptr this) buf n)
      (finally (ffi/free buf)))))
