(ns diplomat.thingy
  "Hand-written reference shape for jolt-diplomat-backend's codegen —
  every generated .clj file should look like this. Empirically verified
  against real Jolt (v0.7.13+); every line here has actually run.
  Synthetic Thingy type exercises the opaque lifecycle, a fallible
  constructor, a plain scalar method, a struct-by-value param with a
  writeable string return, and a slice param — see runtime/src/demo.clj
  for the smoke test that exercises all of it."
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

;; FIXED (severity #4, generation-phase): sizeof AND the is_ok offset must
;; come from the same generated shim, both backed by the real C compiler's
;; sizeof()/offsetof() against the actual struct — matching every other
;; generated per-type file (see e.g. examples/regex/generated/diplomat/
;; regex.clj's c-sizeof-create-result / c-is-ok-offset-create pair). The
;; previous version fetched sz live but read is_ok from a hand-maintained
;; generated-offsets.clj table — two independent numbers describing one
;; struct, with nothing checking they still agreed. offsetof-generated
;; shim replaces the static table; there is now exactly one source of
;; truth for this struct's shape, so the two numbers can't drift apart.
(ffi/defcfn ^:private c-sizeof-try-create-result
  "jolt_sizeof_try_create_result" [] :int)

(ffi/defcfn ^:private c-is-ok-offset-try-create
  "jolt_offsetof_Thingy_try_create_result_is_ok" [] :int)

(defn try-create
  "Thingy/try_create(&str) -> Result<Box<Thingy>, ThingyError>"
  [s]
  (let [sz        (c-sizeof-try-create-result)
        out       (ffi/alloc sz)
        is-ok-off (c-is-ok-offset-try-create)]
    (try
      (c-try-create s (count s) out)
      (dr/unwrap-result!
       (if (= 1 (ffi/read out :uint8 is-ok-off))
         {:ok? true :value (->Thingy (ffi/read out :pointer 0) (atom false))}
         {:ok? false :error (ffi/read out :int 0)}) ;; err shares the union slot
       "Thingy/try-create")
      (finally (ffi/free out)))))

;; --- plain scalar method --------------------------------------------------

(ffi/defcfn ^:private c-value "Thingy_value" [:pointer] :uint8)

(defn value [^Thingy this]
  (c-value (dr/ptr! this)))

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

;; CLEANUP: this file used to defcfn its own private c-simple-write binding
;; to jolt_diplomat_simple_write — the exact same C symbol and signature
;; runtime.clj already binds and wraps as dr/simple-write!. Two FFI
;; bindings to one symbol per generated per-type file was pure codegen
;; duplication; now reuses the shared wrapper instead of re-declaring it.

(defn describe
  "Thingy::describe(&self, ThingyOptions, &mut DiplomatWrite)"
  [^Thingy this {:keys [verbose scale]}]
  (let [buf (ffi/alloc 256)
        w   (ffi/alloc dr/writeable-struct-size)]
    (try
      (dr/simple-write! buf 256 w) ;; NEVER hand-assemble this struct — a
                                    ;; hand-built version with null flush/grow
                                    ;; crashes ("invalid memory reference"),
                                    ;; verified directly; Diplomat's writer
                                    ;; calls flush unconditionally to finalize.
      (c-describe (dr/ptr! this) (if verbose 1 0) (double scale) w)
      (dr/read-writeable! buf w "Thingy/describe")
      (finally (ffi/free buf) (ffi/free w)))))

;; --- slice param, copied not borrowed --------------------------------------

(ffi/defcfn ^:private c-sum-with "Thingy_sum_with" [:pointer :pointer :size_t] :uint)

(defn sum-with
  "Thingy::sum_with(&self, &[u8]) -> u32. `others` is COPIED across the
  boundary by Diplomat's own model, not borrowed."
  [^Thingy this others]
  ;; CLEANUP: replaces a hand-rolled alloc/write/free loop with
  ;; dr/with-primitive-buffer, which already generalizes this marshal (and
  ;; already guards the zero-length case per severity #3 — (max n 1) —
  ;; instead of that guard being re-derived here too).
  (dr/with-primitive-buffer [buf :uint8 others]
    (c-sum-with (dr/ptr! this) buf (count others))))
