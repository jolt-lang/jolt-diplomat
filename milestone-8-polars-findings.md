# Milestone 8 findings — testing the Polars bridge design for real,
  2026-08-16

Follows directly from the Polars interop analysis. Two things were
actually testable in this sandbox; one wasn't, and the honest boundary
between them matters.

## Real polars: genuinely could not build here

Tried, seriously, not just once: pinned `home`, `getrandom`, `ahash` in
sequence chasing transitive `edition2024` requirements (same MSRV wall as
`icu_capi` 2.0, documented in `HANDOFF.md`), across eight rounds — further
than `icu_capi` needed. Even the innermost `polars-core` crate alone, with
default features disabled, pulls a `wasm-bindgen`/`getrandom`-js chain
(likely from `ahash`'s build-time RNG seeding, apparently unconditional
regardless of target). This is a real, structural toolchain limit, not a
"try one more pin" situation — confirms and reinforces the handoff doc's
core recommendation: this needs a real machine with current rustc before
any of the Polars design can be tested against actual Polars logic or
actual Arrow buffers.

## What WAS real and testable: the bridge architecture's FFI shape,
   honestly disclosed as a stand-in

Built `polars-bridge-stub` — **not real Polars**, explicitly not claiming
to be. A tiny hand-rolled crate with the identical FFI *shape* the design
calls for: opaque `StubDataFrame` handle, fallible CSV-style constructor
(a comma-split parser, not real CSV logic), and critically, the one thing
the design analysis flagged as **genuinely unproven**: a callback stored
and invoked *repeatedly*, from a *real spawned OS thread*, standing in for
a `rayon` worker — as opposed to Milestone 6's proven case (synchronous,
single invocation, same call frame).

### Finding 1: Diplomat's callback lowering rejects multiple trait bounds

Tried the natural signature first — `impl Fn(f64) -> f64 + Sync` — since
that's what a real thread-safe callback parameter needs at the Rust type
level. Diplomat's macro rejected it outright:

```
error: custom attribute panicked
  = help: message: not yet implemented: Currently don't support
    implementing multiple traits
```

This is a **real, verified limitation** relevant directly to Polars: its
actual parallel-map APIs typically require `F: Fn(T) -> T + Send + Sync`
in their public signature. You cannot mirror that signature directly
through `#[diplomat::bridge]` as of `diplomat_core` 0.10.0 — confirmed by
trying it, not inferred from documentation (there wasn't any covering
this).

**The workaround, found and verified**: keep the *exposed* Diplomat
signature bound-free (`impl Fn(f64) -> f64`, which Diplomat does accept,
per Milestone 6), and assert `Send`/`Sync` manually and unsafely *inside*
the bridge function body, via a local newtype:

```rust
struct AssertSendSync<T>(T);
unsafe impl<T> Send for AssertSendSync<T> {}
unsafe impl<T> Sync for AssertSendSync<T> {}
```

Justified, not hand-waved: the underlying `DiplomatCallback` is just a C
function pointer plus a `void*` data pointer — both trivially safe to
move across threads regardless of what Rust closure they came from. The
unsafety is real but narrow and auditable.

### Finding 2: the workaround itself has a sharp, non-obvious edge — Rust
   2021's disjoint closure capture silently defeats it

First attempt at the workaround still failed, with an error pointing at
the *original* unwrapped type, not the wrapper:

```rust
let handle = s.spawn(|| {
    let f = &wrapped.0;   // captures wrapped.0 DIRECTLY, not `wrapped`
    ...
});
```

Rust 2021's precise/disjoint closure capture captures the individual
*field* `wrapped.0` when only that field is used, bypassing the wrapper
struct — and its `unsafe impl Send` — entirely. The fix is a well-known
but easy-to-miss pattern: force whole-value capture with a redundant
rebinding immediately inside the closure:

```rust
let handle = s.spawn(|| {
    let wrapped = &wrapped;  // forces capturing `wrapped` as a unit
    let f = &wrapped.0;
    ...
});
```

Compiled clean after this fix. **Worth flagging in any real bridge code**
using this pattern — it's exactly the kind of thing that silently
compiles wrong (well, doesn't compile — but for a confusing reason
pointing at the wrong type) if you don't know to look for it.

### Real end-to-end test, through actual Jolt

Generated the real header (`diplomat-tool c`), confirmed the same
`DiplomatCallback_*` ABI shape as Milestone 6's `apply_callback` —
`{data, run_callback, destructor}`, by value, same shim-decomposition
rule applies unchanged. Hand-wrote the shim, compiled, linked, ran
through real Jolt:

```
:row-count 5
:sum 15.0
:destructor-fired :call-count 5
:map-reduce-threaded-result 55.0
:expected 55.0
```

`1² + 2² + 3² + 4² + 5² = 55`, exact. The callback fired **5 times**
(`call-count` reached 5, confirmed via a Clojure atom incremented once
per invocation) from a **genuinely spawned OS thread** (`std::thread::
scope` + `s.spawn`, not the calling thread), under `:collect-safe`, with
the destructor firing correctly **after all 5 invocations completed** —
not after the first, not leaking, not double-freeing.

## What this proves, precisely — and what it still doesn't

**Proven, meaningfully beyond Milestone 6**: `:collect-safe` correctly
handles a callback invoked *multiple times* from a *genuinely different*
thread than the one that registered it, with correct destructor timing
across the whole sequence of invocations. This substantially de-risks
the "Phase 3" concern from the original Polars analysis — the mechanism
itself handles repeated cross-thread invocation correctly, which is much
closer to what a real `rayon`-parallelized `.map_elements()` would
actually do than the original single-shot test.

**Still NOT proven**: this stub's `std::thread::scope` still *blocks*
until the spawned thread completes before `map_reduce_threaded` returns
to the C caller — the whole callback lifecycle (all invocations plus the
destructor) still completes synchronously relative to the overall call,
same as Milestone 6. A callback that genuinely **outlives** the
registering call — registered once, then invoked later at some
independent, asynchronous point (an event loop firing it after the
original Jolt call has already returned and moved on) — is a
structurally different case, not tested here, and not something this
result should be stretched to cover. If Polars' actual execution model
turns out to need that shape (plausible for lazy/streaming execution,
less likely for a single `.map_elements()` call), it needs its own
dedicated test before being trusted.

## Net assessment

Two real, non-obvious, load-bearing findings for anyone building this
bridge for real (the trait-bound rejection and its disjoint-capture-aware
workaround), plus a substantially stronger proof of the callback safety
mechanism than existed before this session — genuinely testable and
tested despite real Polars itself being out of reach in this environment.
The honest boundary drawn above (proven: repeated + cross-thread +
synchronous-overall; not proven: genuinely outliving the call) is the
right scope for what to trust going into a real-machine continuation.
