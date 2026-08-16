# Milestone 4 findings — native-interop.html cross-check + final full-file
  verification, 2026-08-15

## Doc fetch

Read `https://jolt-lang.net/docs/native-interop.html` in full (the user's
own link). This is the authoritative source milestones 1-3 were mostly
inferring around. Cross-checked every design decision against it.

## Everything empirically found in Milestones 1-3 is confirmed BY THE DOCS

- **No struct type exists, at all, either direction.** The doc's own type
  table is exhaustive: `:int :uint :long :ulong :int64 :uint64 :size_t
  :ssize_t :iptr :uptr :double :float :char :uint8 (:u8/:byte) :pointer
  (:void*) :string :void`. No struct, no composite. Combined with "There
  is no automatic struct introspection... you manage it, the way you
  would in C" in the intro — this isn't a gap the docs missed, it's the
  documented design. The shim-based workaround wasn't a workaround for a
  documentation blind spot; it's the only approach the FFI was ever
  designed to support.
- **The Result-via-out-pointer shim IS the documented pattern**, just
  applied to a case the doc doesn't cover directly. The "Out-parameters"
  section's own example (`sqlite3_open(path, &db)`: alloc a cell, pass its
  address, read it back, free in `finally`) is structurally identical to
  what `try-create`'s shim does for `Thingy_try_create_result`.
- **The generated-offset-table approach for structs matches the doc's own
  "Structs by offset" section precisely** — same pattern (`ffi/alloc`,
  hand a byte-offset table to `ffi/read`/`ffi/write`), same caveat
  ("Offsets and sizes are platform-specific... keeps a per-OS offset
  where macOS and Linux disagree") that `offset-gen` generalizes instead
  of hand-copying per platform.
- **The checklist items are all satisfied**: library declared (would be,
  in a real deps.edn `:jolt/native` entry, not exercised in this file-only
  spike), every `ffi/alloc` wrapped in `try`/`finally`, `ex-info` thrown on
  the error path, offsets kept correct (now generated, not hand-copied).

## One real doc/implementation discrepancy found

The doc renders `(ffi/null)` and `(ffi/null? p)` — both with parens,
implying `null` is a zero-arg function. Empirically, against the real
v0.7.13 binary: `ffi/null` bare is the value `0`; `(ffi/null)` throws
`class java.lang.Long cannot be cast to class clojure.lang.IFn`. The
symbol list read directly from the binary (`jolt.ffi`'s `ns-publics`)
does contain a bare `null` var alongside `null?`, consistent with the
value interpretation, not the doc's parenthesized rendering. Likely the
doc reflects a different version's calling convention, or is a
documentation-generator artifact treating every API entry uniformly with
parens regardless of arity — not confirmed which. Our code already uses
the correct (value) form, verified working; flagging this as a doc
inconsistency worth reporting upstream, not a code fix needed on our side.

## Two real bugs caught in review, before this ran — now fixed

Neither was previously executed, so neither had been caught:

1. **`O-len`/`writeable-struct-size` were `^:private` in `runtime.clj`**
   but referenced via `dr/` from `thingy.clj` — would have failed to
   resolve. Made public (they're meant to be consumed by every generated
   per-type file, matching how `diplomat_runtime.hpp` constants are meant
   to be used by every generated C++ header).
2. **`with-opaque` called a generic `(close! binding)`, but `defopaque`
   only ever defined a type-specific `close-Thingy!`** — `close!` was
   never actually defined anywhere, so `with-opaque` would have thrown an
   unresolved-symbol error on first use. Fixed with a `Closeable` protocol,
   `extend-type`'d per opaque type inside `defopaque`, so `close!`
   dispatches correctly regardless of which type is in hand — this is
   the idiomatic Clojure/Jolt fix, not a patch.

## Final verification: the actual deliverable files, unmodified, full run

Built a real minimal project (`deps.edn` with `{:paths ["src"]}`,
`src/diplomat/{runtime,generated_offsets,thingy}.clj`, `src/demo.clj`) and
ran it with `jolt run src/demo.clj` against the real shim library and real
compiled Rust static lib built in Milestone 1. Zero simulation, zero
inline one-liners — this is the actual shipped file content running
end to end:

```
:value 42
:describe-terse 42
:describe-verbose Thingy(value=42, scale=2.5)
:sum-with 48
:error-path-ok #:diplomat{:error 0}
```

Every value correct, including the `with-opaque`/`close!` protocol fix
working on the first real invocation after the review-time correction.

## Net status

The full chain — Rust/Diplomat crate → generated C header → generated
shim → `diplomat.runtime` → `diplomat.thingy` → real Jolt project — is now
verified working end to end, against the real toolchain on every layer,
cross-checked against Jolt's own authoritative documentation with no
remaining contradictions. The design in this repo is no longer a spike in
the sense of "unverified" — it's a working reference implementation for
one opaque type with a fallible constructor, a by-value-struct-param
method, a writeable string return, and a slice param. What remains is
scope, not correctness: generalizing `jolt-backend/mod.rs` to emit this
pattern automatically from Diplomat's HIR for an arbitrary crate, rather
than the hand-written `Thingy`-specific version verified here.
