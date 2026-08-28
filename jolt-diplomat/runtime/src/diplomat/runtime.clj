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

;; A generic close! needs single dispatch across every opaque type
;; defopaque ever defines — a protocol, extended per-type inside the
;; macro, rather than a bare fn each type would otherwise shadow. (The
;; original draft called (close! x) from with-opaque but defopaque only
;; ever defined a type-specific close-Thingy! — with-opaque would have
;; failed to resolve on first use. Caught and fixed here before this ever
;; ran against real Jolt.)
(defprotocol Closeable
  (close! [this]))

;; FIXED (severity #1): `closed?` used to be a plain record field, flipped
;; via `(assoc obj# :closed? true)` — assoc returns a NEW record, so the
;; caller's original binding never observed the flag change. The
;; `^:volatile-mutable` hint on the field was also inert: defrecord (unlike
;; deftype) has no mutable-field support, so it was silently dropped. Net
;; effect: the "already closed?" guard never actually guarded anything,
;; and a second close! on the same binding called the C destroy fn again
;; (double-free / use-after-free). Fix: hold the flag in an atom, which is
;; the same object no matter how many times the record is copied/rebound —
;; the guard now actually shares state across all refs to one opaque value.
(defmacro defopaque
  "Defines a record wrapping a foreign pointer plus a destroy fn, extended
  to the Closeable protocol so close! dispatches correctly regardless of
  which opaque type is in hand. destroy-symbol is the C symbol Diplomat
  generated for this type's _destroy function.

  Usage (generated code emits this once per opaque type):
    (defopaque Thingy \"Thingy_destroy\")"
  [type-sym destroy-symbol]
  `(do
     (defrecord ~type-sym [~'ptr ~'closed-atom])

     (ffi/defcfn ~(symbol (str "c-" (name type-sym) "-destroy")) ~destroy-symbol [:pointer] :void)

     (extend-type ~type-sym
       Closeable
       (close! [obj#]
         (when (compare-and-set! (:closed-atom obj#) false true)
           (~(symbol (str "c-" (name type-sym) "-destroy")) (:ptr obj#)))))))

;; close! guards against a double-free, but nothing stopped a *use* after
;; close — every generated accessor read (:ptr this) directly, handing a
;; possibly-already-freed pointer straight to C. with-opaque protects the
;; scoped case; anything that escapes scope (stored, returned, closed
;; early inside a longer body) had no guard at the point of use. Every
;; generated pointer read now goes through this instead of a bare (:ptr x).
(defn ptr!
  "Reads an opaque's pointer, guarding against use-after-close. A closed
  opaque throws here instead of handing a dangling pointer to C."
  [opaque]
  (if @(:closed-atom opaque)
    (throw (ex-info "use of closed opaque value" {:diplomat/type (type opaque)}))
    (:ptr opaque)))

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
  message() method rather than a raw int.

  When message-fn is given, error is an owned opaque the generated Result
  branch just allocated (closed-atom starts false — see emit_opaque_wrap).
  Nothing in a catch clause was ever required to close it: every real
  caller (across all 8 example crates) only reads ex-message, never holds
  onto ex-data's opaque past the catch — so this closes it here, right
  after extracting the string, instead of leaking it on every error path.
  :diplomat/error in ex-data is the extracted message string, not the
  (now-closed) opaque — a caller can't use a closed opaque anyway, and
  this avoids handing out a handle that dr/ptr! would immediately reject.
  The raw-int error case (no message-fn) is unaffected — there's no
  opaque to close, so error is passed through as-is."
  ([{:keys [ok? value error] :as _raw-result} method-name]
   (unwrap-result! _raw-result method-name nil))
  ([{:keys [ok? value error] :as _raw-result} method-name message-fn]
   (if ok?
     value
     (if (and message-fn error)
       (let [text (try (String. (message-fn error))
                        (finally (close! error)))] ;; close even if message-fn itself throws
         (throw (ex-info (str method-name " failed: " text) {:diplomat/error text})))
       (throw (ex-info (str method-name " failed") {:diplomat/error error}))))))

;; -----------------------------------------------------------------------
;; DiplomatWriteable — Jolt owns the buffer the whole time, so there's no
;; ownership ambiguity the way a bare :string return would have.
;; -----------------------------------------------------------------------

(def ^:private initial-buffer-size 256)
(def ^:private max-buffer-size (* 16 1024 1024)) ;; ponytail: sane ceiling, raise if a real payload needs more

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

;; FIXED (severity #2, deduped): the grow_failed check — read the flag,
;; throw with the caller's label if truncation happened, else read back
;; the actual bytes — was hand-repeated at every writeable-out callsite
;; (writeable-capture, writeable-capture-when, and thingy.clj's describe).
;; One helper now; a new per-type generated file gets this by calling it,
;; not by re-deriving the 3-line if/throw/else pattern again.
(defn read-writeable!
  "After calling into Rust with buf/w as the writeable-out args, reads
  back the bytes Rust wrote — or throws if the fixed-size buf couldn't
  hold the output (DiplomatWrite's grow_failed flag). label names the
  call for the exception message.

  Direct callers (e.g. a generated describe method that owns its own
  fixed buf) get the original throw-on-overflow contract unchanged.
  writeable-capture/writeable-capture-when below don't call this on the
  overflow path — they retry with a bigger buffer instead; see grow!"
  [buf w label]
  (if (not= 0 (ffi/read w :uint8 O-grow-failed))
    (throw (ex-info (str label ": buffer grow failed, output truncated")
                     {:diplomat/buffer-size initial-buffer-size}))
    (let [n (ffi/read w :size_t O-len)]
      (ffi/read-bytes buf n))))

(defn- writeable-grow-failed? [w]
  (not= 0 (ffi/read w :uint8 O-grow-failed)))

(defn- writeable-read-bytes [buf w]
  (ffi/read-bytes buf (ffi/read w :size_t O-len)))

;; Doubles the buffer and retries rather than throwing on the first
;; overflow — a long URL, a verbose describe(), or deeply nested JSON can
;; legitimately exceed 256 bytes, and truncating that to an exception was
;; a correctness bug on valid input, not just a fixed-cost simplification.
;; f must be safe to call more than once: it only writes into buf/w, no
;; other side effects, so a retry from scratch is always sound. Capped at
;; max-buffer-size so a malformed/adversarial input can't turn a string
;; return into unbounded allocation.
(defn- capture-loop [f label read-fn]
  (loop [size initial-buffer-size]
    (let [buf (ffi/alloc size)
          w   (ffi/alloc writeable-struct-size)]
      (try
        (c-simple-write buf size w) ;; NOT hand-assembled — see writeable-capture's docstring
        (let [f-result (f w)]
          (if (writeable-grow-failed? w)
            (do (ffi/free buf) (ffi/free w)
                (if (>= size max-buffer-size)
                  (throw (ex-info (str label ": output exceeds max buffer size")
                                   {:diplomat/buffer-size size}))
                  (recur (* size 2))))
            (let [result (read-fn f-result buf w)]
              (ffi/free buf) (ffi/free w)
              result)))
        (catch Throwable t
          (ffi/free buf) (ffi/free w)
          (throw t))))))

(defn writeable-capture
  "Calls f with a fresh DiplomatWrite pointer as its writeable-out
  argument, and returns the UTF-8 string Rust wrote into it. f is a fn of
  one arg: the writeable pointer.

  Grows and retries (doubling from 256 bytes, capped at max-buffer-size)
  if the output didn't fit, instead of throwing on the first overflow —
  see capture-loop.

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
  (capture-loop f "writeable-capture" (fn [_f-result buf w] (writeable-read-bytes buf w))))

;; -----------------------------------------------------------------------
;; Struct-by-value — generated offset table drives read/write, replacing
;; the native-interop guide's "write the layout out by hand" pattern.
;; -----------------------------------------------------------------------

(defn writeable-capture-when
  "Like writeable-capture but returns nil when f returns a falsy value.
  f receives the DiplomatWrite pointer and should return truthy on success.
  Grows and retries on overflow — see capture-loop."
  [f]
  (capture-loop f "writeable-capture-when"
                (fn [f-result buf w]
                  (when (not= 0 f-result) (writeable-read-bytes buf w)))))

(defn read-u16
  "Read a little-endian uint16 from ptr at byte offset. Jolt ffi has no
  16-bit read type, so we combine two uint8 reads."
  [ptr offset]
  (bit-or (ffi/read ptr :uint8 offset)
          (bit-shift-left (ffi/read ptr :uint8 (+ offset 1)) 8)))

