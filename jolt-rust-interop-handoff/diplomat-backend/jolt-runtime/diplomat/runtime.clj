(ns diplomat.runtime
  "Jolt-side counterpart to Diplomat's diplomat_runtime.hpp — every generated
  file calls into this, so the boundary conventions (opaque lifetime, result
  unwrapping, writeable strings, struct-by-value marshaling) live in one
  place, hand-written once, instead of being re-derived per binding.

  This is the deliverable from PLAN.md Milestone 5. Nothing here is
  Diplomat-specific beyond the shapes its C backend emits (DiplomatWriteable,
  flat Result structs, opaque-behind-a-pointer) — it's a thin layer over
  jolt.ffi."
  (:require [jolt.ffi :as ffi]))

;; -----------------------------------------------------------------------
;; Opaque lifetime — Plan A (guardian) vs Plan B (explicit close!)
;;
;; Plan A needs a jolt.ffi addition that doesn't exist yet as of this
;; writing: a Chez-guardian-backed finalizer hook. File that as an RFC
;; against jolt-lang/jolt; until it lands, Plan B ships today with no core
;; changes and matches jolt.ffi's existing "you manage it, the way you
;; would in C" posture documented in the native-interop guide.
;; -----------------------------------------------------------------------

(def ^:dynamic *opaque-lifecycle*
  "Either :explicit (Plan B, default — caller must (close! x) or use
  with-open) or :guarded (Plan A — requires jolt.ffi finalizer support)."
  :explicit)

;; A generic close! needs single dispatch across every opaque type
;; defopaque ever defines — a protocol, extended per-type inside the
;; macro, rather than a bare fn each type would otherwise shadow. (The
;; original draft called (close! x) from with-opaque but defopaque only
;; ever defined a type-specific close-Thingy! — with-opaque would have
;; failed to resolve on first use. Caught and fixed here before this ever
;; ran against real Jolt.)
(defprotocol Closeable
  (close! [this]))

(defmacro defopaque
  "Defines a record wrapping a foreign pointer plus a destroy fn, extended
  to the Closeable protocol so close! dispatches correctly regardless of
  which opaque type is in hand. destroy-symbol is the C symbol Diplomat
  generated for this type's _destroy function.

  Usage (generated code emits this once per opaque type):
    (defopaque Thingy \"Thingy_destroy\")"
  [type-sym destroy-symbol]
  `(do
     (defrecord ~type-sym [~'ptr ~'^:volatile-mutable closed?])

     (ffi/defcfn ~(symbol (str "c-" (name type-sym) "-destroy")) ~destroy-symbol [:pointer] :void)

     (extend-type ~type-sym
       Closeable
       (close! [obj#]
         (when-not (:closed? obj#)
           (~(symbol (str "c-" (name type-sym) "-destroy")) (:ptr obj#))
           (assoc obj# :closed? true))))))

(defmacro with-opaque
  "Like with-open, scoped to a Diplomat opaque value. Always closes even on
  exception. This is the Plan B ergonomics story — one macro instead of
  manual try/finally at every call site."
  [[binding ctor] & body]
  `(let [~binding ~ctor]
     (try
       ~@body
       (finally (close! ~binding)))))

(defmacro when-opaque
  "Like with-opaque but skips body and returns nil when the opaque value is
  nil — useful for optional return values like array-get on an out-of-bounds
  index, where the caller would otherwise need a manual nil-guard around
  with-opaque."
  [[binding ctor] & body]
  `(let [~binding ~ctor]
     (if (nil? ~binding)
       nil
       (try
         ~@body
         (finally (close! ~binding))))))

(defmacro load!
  "Load the Rust cdylib and its generated shim dylib for a Diplomat-bound
  crate. lib-name is the snake_case name from [lib] name in Cargo.toml
  (e.g. \"json_capi\"). demo-dir is the directory containing both
  lib{lib-name}.dylib (under {lib-name}/target/release/) and
  lib{lib-name}_shim.dylib.

  Example:
    (dr/load! \"/path/to/json-demo\" \"json_capi\")"
  [demo-dir lib-name]
  `(do
     (ffi/load-library (str ~demo-dir "/" ~lib-name "/target/release/lib" ~lib-name ".dylib"))
     (ffi/load-library (str ~demo-dir "/lib" ~lib-name "_shim.dylib"))))

(defmacro with-primitive-buffer
  "Marshals a Clojure seq of numbers to a temp C buffer of the given
  jolt.ffi element type (elem-type), for the scope of body, freeing it
  afterward. Generalizes with-u8-buffer (kept below for anything already
  depending on it) to any element width — needed because Diplomat emits
  a distinctly-named DiplomatXView per element type (DiplomatI32View,
  DiplomatU8View, ...), and jolt.ffi has no single generic slice-write
  helper, only element-typed ffi/write."
  [[buf-sym elem-type seq-expr] & body]
  `(let [items# (vec ~seq-expr)
         n#     (count items#)
         w#     (ffi/sizeof ~elem-type)
         ~buf-sym (ffi/alloc (max (* n# w#) 1))]
     (try
       (dotimes [i# n#]
         (ffi/write ~buf-sym ~elem-type (* i# w#) (nth items# i#)))
       ~@body
       (finally (ffi/free ~buf-sym)))))

(defmacro with-u8-buffer
  "Marshals a Clojure seq of byte values to a temp C buffer for the scope
  of body, freeing it afterward. Used by generated bindings for slice
  parameters (e.g. Thingy::sum_with's &[u8]) — the shim wants a raw
  pointer+length; this is the seq->buffer marshaling the hand-written
  version did inline, factored out so generated code can reuse it."
  [[buf-sym seq-expr] & body]
  `(let [n#   (count ~seq-expr)
         ~buf-sym (ffi/alloc (max n# 1))]
     (try
       (doseq [[i# v#] (map-indexed vector ~seq-expr)]
         (ffi/write ~buf-sym :uint8 i# v#))
       ~@body
       (finally (ffi/free ~buf-sym)))))

;; -----------------------------------------------------------------------
;; Result<T, E> -> ex-info.
;;
;; REVISED after milestone-1-findings.md finding #2: real Diplomat emits
;; a NAMED, BY-VALUE return struct per call site —
;;   typedef struct Thingy_try_create_result {
;;     union { Thingy* ok; ThingyError err; }; bool is_ok;
;;   } Thingy_try_create_result;
;;   Thingy_try_create_result Thingy_try_create(DiplomatStringView s);
;; — not the out-pointer design this fn originally assumed.
;;
;; No evidence found (doc search, milestone-1) that jolt.ffi's defcfn
;; supports a struct-by-value RETURN type — the native-interop guide only
;; covers structs in parameter position. Default: the jolt backend emits
;; a small generated C shim per Result-returning function that takes the
;; same args plus an out-pointer, calls the real fn, and writes the result
;; through it — sidestepping the open question entirely rather than
;; blocking on it. See jolt-backend/shim_template.c for the generated
;; shape. unwrap-result! below is written against the shim's output,
;; which normalizes to the same {:ok? :value :error} shape regardless of
;; how the underlying struct-by-value return is actually solved.
;; -----------------------------------------------------------------------

(defn unwrap-result!
  "raw-result: {:ok? bool :value v :error e}.
  Optional message-fn: called with the error value to produce a string
  for the exception message — use when the error is an opaque with a
  message() method rather than a raw int."
  ([{:keys [ok? value error] :as _raw-result} method-name]
   (unwrap-result! _raw-result method-name nil))
  ([{:keys [ok? value error] :as _raw-result} method-name message-fn]
   (if ok?
     value
     (let [msg (if (and message-fn error)
                 (str method-name " failed: " (String. (message-fn error)))
                 (str method-name " failed"))]
       (throw (ex-info msg {:diplomat/error error}))))))

;; -----------------------------------------------------------------------
;; DiplomatWriteable — Jolt owns the buffer the whole time, so there's no
;; ownership ambiguity the way a bare :string return would have.
;; -----------------------------------------------------------------------

(def ^:private initial-buffer-size 256)

;; REAL layout, from offset-gen run against out-c-real/diplomat_runtime.h
;; (2026-08-15, diplomat 0.10.0) — see diplomat.generated-offsets. The
;; pre-spike hand copy assumed a 48-byte struct with no grow_failed field;
;; the real struct is 56 bytes, and the type is DiplomatWrite, not
;; DiplomatWriteable (name drifted across Diplomat versions vs. the book).
;; Confirms exactly why this must never be hand-maintained again.
(require '[diplomat.generated-offsets :as offsets])

;; PUBLIC (not ^:private): per-type codegen files (e.g. diplomat.thingy)
;; need O-len and writeable-struct-size to read a DiplomatWrite's length
;; back out after a call — caught in review before this ever ran.
(def O-context (get-in offsets/diplomat-write-layout [:fields :context]))
(def O-buf     (get-in offsets/diplomat-write-layout [:fields :buf]))
(def O-len     (get-in offsets/diplomat-write-layout [:fields :len]))
(def O-cap     (get-in offsets/diplomat-write-layout [:fields :cap]))
(def O-grow-failed (get-in offsets/diplomat-write-layout [:fields :grow-failed]))
(def writeable-struct-size (:size offsets/diplomat-write-layout))

;; Generated per-crate by the backend, alongside every other shim — see
;; milestone-3-findings.md. Wraps diplomat_simple_write, which returns
;; DiplomatWrite BY VALUE and must never be called directly from Jolt.
(ffi/defcfn ^:private c-simple-write
  "jolt_diplomat_simple_write" [:pointer :size_t :pointer] :void)

(defn simple-write!
  "Public wrapper so generated per-type files can construct a DiplomatWrite
  without reaching past this namespace's privates. NEVER hand-assemble
  this struct — see writeable-capture's docstring for why."
  [buf cap w]
  (c-simple-write buf cap w))

(defn writeable-capture
  "Calls f with a fresh DiplomatWrite pointer as its writeable-out
  argument, and returns the UTF-8 string Rust wrote into it. f is a fn of
  one arg: the writeable pointer.

  CORRECTED per milestone-3-findings.md: the DiplomatWrite struct is
  NEVER hand-assembled here, even with correct offsets — a hand-built
  struct with null flush/grow function pointers crashes
  ('invalid memory reference'), verified directly against real jolt
  v0.7.13. flush is evidently called unconditionally to finalize.
  Always constructed via the library's own diplomat_simple_write,
  itself routed through a generated shim since it ALSO returns
  DiplomatWrite by value (56 bytes) — declaring its return as :pointer
  from Jolt does not error, it silently returns garbage (SysV ABI: >16
  byte struct returns use a hidden out-pointer neither side agreed on).
  c-simple-write below is exactly jolt_diplomat_simple_write from
  jolt-backend/shim-verified/thingy_shim2.c, generalized: the backend
  emits one such shim per Diplomat crate, not per type."
  [f]
  (let [buf (ffi/alloc initial-buffer-size)
        w   (ffi/alloc writeable-struct-size)]
    (try
      (c-simple-write buf initial-buffer-size w) ;; NOT hand-assembled
      (f w)
      (let [n (ffi/read w :size_t O-len)]
        (ffi/read-bytes buf n))
      (finally
        (ffi/free buf)
        (ffi/free w)))))

;; -----------------------------------------------------------------------
;; Struct-by-value — generated offset table drives read/write, replacing
;; the native-interop guide's "write the layout out by hand" pattern.
;; -----------------------------------------------------------------------

(defn read-u16
  "Read a little-endian uint16 from ptr at byte offset. Jolt ffi has no
  16-bit read type, so we combine two uint8 reads."
  [ptr offset]
  (bit-or (ffi/read ptr :uint8 offset)
          (bit-shift-left (ffi/read ptr :uint8 (+ offset 1)) 8)))

(defn struct->ptr
  "field-offsets: {:field-name [byte-offset ffi-type]}, generated per-type
  by the backend (PLAN.md Milestone 6). m: a Clojure map with matching
  keys. Allocates and returns a pointer the caller must free (or wrap in
  with-opaque-style scoping) — this is a transient marshaling buffer, not
  a value with independent lifetime, so a plain try/finally at the call
  site is the right amount of ceremony, not a guardian."
  [field-offsets size m]
  (let [p (ffi/alloc size)]
    (doseq [[field [offset ffi-type]] field-offsets]
      (ffi/write p ffi-type offset (get m field)))
    p))

(defn ptr->struct
  "Inverse of struct->ptr, for structs Rust returns by value into
  caller-provided memory."
  [field-offsets ptr]
  (into {}
        (for [[field [offset ffi-type]] field-offsets]
          [field (ffi/read ptr ffi-type offset)])))
