# Diplomat → Jolt backend: spike plan

Goal: prove a `jolt` Diplomat backend end-to-end against a small crate before
pointing it at ICU4X. Everything here is scoped to be checkable in isolation —
each milestone should compile/run before starting the next.

## Why this shape

Diplomat's HIR already resolves every type it needs to cross the boundary
(opaques, structs, enums, `Result`, slices, strings-via-writeable). The
backend's only job is to walk that HIR and emit `.clj` instead of `.h`. Nothing
here requires changes to `jolt.ffi` itself — the marshaling all fits inside
scalars/pointers/manual-memory — **except one optional piece**, called out in
Milestone 3.

## Spike crate surface

One small crate (`spike-crate/`) exercising every shape that matters:

- an opaque type (`Thingy`) with a fallible constructor → `Result<Box<T>, E>`
- a plain method → scalar return
- a string-returning method → `DiplomatWriteable`
- a non-opaque struct-by-value param (`ThingyOptions`) → the "structs by
  offset" case
- a slice param → copied, not borrowed (per Diplomat's own model)

If the backend handles all five, it handles the overwhelming majority of a
real crate's surface, ICU4X included.

## Milestones

1. **Build the spike crate under `diplomat::bridge`, generate the C header.**
   `cargo install diplomat-tool && diplomat-tool -e spike-crate/src/lib.rs -c
   config.toml c out/`. Read `out/Thingy.h` before writing a single line of
   backend code — confirms this Diplomat version's actual output matches the
   book's docs (they can drift release to release).

2. **`jolt` backend skeleton, opaque types only.** `tool/src/jolt/mod.rs`
   walking `TypeContext` and emitting one `defcfn` per method plus a
   `defrecord` wrapping the pointer. Get `Thingy_try_create` /
   `Thingy_value` round-tripping from a `jolt run` REPL before adding
   anything else.

3. **Decide opaque lifetime management — this is the one open dependency.**
   `jolt.ffi` as documented has no finalizer/guardian hook (Chez itself
   supports guardians; Jolt doesn't currently expose one). Two options,
   not mutually exclusive:
   - **Plan A (needs a small jolt.ffi addition):** expose Chez guardians as
     `ffi/register-finalizer!`, generate an automatic `_destroy` call on GC.
     File this as an issue/RFC against jolt-lang/jolt early — it's small
     (a guardian is a ~10-line Chez primitive) and every future binding
     benefits, not just this one.
   - **Plan B (ships today, no core changes):** generate an explicit
     `close!` per opaque type plus a `with-thingy` macro
     (`(with-open [t (Thingy/try-create "5")] ...)`), matching Jolt's
     existing "you manage it, the way you would in C" posture. Safer
     default; less seamless.
   Ship Plan B first, swap to Plan A once the jolt.ffi change lands.
   `diplomat/runtime.clj` (Milestone 5) implements both so this is a
   config flag, not a rewrite.

4. **`Result<T, E>` → `ex-info`.** One codegen rule, used everywhere:
   `Ok` unwraps to the value, `Err` throws `(ex-info "<method> failed"
   {:diplomat/error <decoded-error>})`. Confirms against the spike crate's
   `try_create`.

5. **`diplomat.runtime` — the Jolt-side counterpart to `diplomat_runtime.hpp`.**
   Three helpers every generated file calls into: `writeable-capture` (string
   returns), `unwrap-result!` (the flat tagged union), `defopaque`/`with-open`
   (lifetime, per Milestone 3). Write this once, by hand — see
   `jolt-runtime/diplomat/runtime.clj` in this spike for a first pass.

6. **Struct-by-value → generated offset table.** The backend already has
   every field's type and order from the HIR; emit an EDN offset map
   alongside the record instead of hand-copying offsets (retires that whole
   section of the native-interop guide). Test against `ThingyOptions`.

7. **Slices → `ffi/write-array`, documented as copied.** Test against
   `sum_with`.

8. **End-to-end:** static-link the spike crate (`:jolt/native {:static
   {:archive ...}}`), call every method from a `jolt build` binary, not just
   `jolt run`.

9. **Stretch goal:** point the same backend at one real ICU4X component
   (e.g. a date formatter) with zero backend changes. Any gap found here is
   the actual scope of "what's left," not a guess.

## What "done" looks like for the spike

A `jolt run spike/demo.clj` that constructs a `Thingy`, calls a
writeable-returning method, passes a by-value options struct, passes a slice,
and triggers the error path — with no manual FFI code outside generated files
and `diplomat.runtime`.
