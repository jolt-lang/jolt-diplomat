# Milestone 6 findings — callbacks: threading safety, a real docs gap, and
  the actual DiplomatCallback ABI, 2026-08-16

The last remaining gap from Milestone 5 ("callbacks — genuinely new design
territory, not mechanical"). Unlike every other milestone, this one
started by re-reading the authoritative source and finding it silent on
the exact mechanism needed — so the entire chapter had to be established
empirically, the same way the rest of this project has worked, just with
no documentation floor to stand on at all this time.

## Correction to an earlier claim in this project

Several turns before this milestone, discussing the egui closure problem,
I asserted that Jolt's docs "name a GUI main loop as the example" for
`foreign-callable` + `:collect-safe`. **That claim does not hold up.**
Refetching `native-interop.html` in full for this milestone shows the
page never mentions `foreign-callable` or `:collect-safe` anywhere — it
documents only `defcfn`/`foreign-fn` (Jolt calling out) and
`export!`/`jolt_library_init`/`jolt_lookup` (C calling into a whole
embedded Jolt library, explicitly single-threaded only, per that
section's own text: *"the callbacks are not registered as collect-safe,
so entering them from another OS thread the runtime never activated is
undefined behavior"* — which is about `export!`'s functions, not
individual per-call callbacks). The earlier claim was an unverified
inference, not something actually read from the page. Recorded here so
it doesn't stand uncorrected in the project history.

`foreign-callable` and `free-callable` genuinely exist — confirmed
earlier (Milestone 3) directly from `jolt.ffi`'s real `ns-publics` list —
but their calling convention and `:collect-safe` semantics are
**completely undocumented**. Everything below was established by testing
the real v0.7.13 binary directly.

## `foreign-callable`'s real shape

```clojure
(ffi/foreign-callable (fn [...] ...) [arg-types] ret-type [:collect-safe])
```

Confirmed via `(meta #'ffi/foreign-callable)` → `{:macro true}`, then
proven end-to-end with a genuine C consumer — `qsort`, with a Jolt closure
as the comparator:

```
:sorted [1 2 3 4 5]
```

## The central finding: `:collect-safe` is real, and its absence is a
   silent-until-it-happens crash

This required a controlled experiment, because a flag that's silently
*accepted* proves nothing — `:bogus-flag` was also accepted with no
error, same as `:collect-safe`. Only a behavioral difference proves the
flag does something.

| Scenario | Result |
|---|---|
| Callback invoked on the same thread that registered it | Works, no flag needed |
| Callback invoked from a genuinely new pthread, no flag | **`nonrecoverable invalid memory reference`, SIGABRT, exit 134** |
| Same, with `:collect-safe` | **Works correctly, exit 0** |
| Same, with `:bogus-flag` instead (control) | **Still crashes identically** — isolates the effect to `:collect-safe` specifically |
| 50 concurrent OS threads, `:collect-safe`, shared mutable `atom` | All 50 succeed, results all correct (`0² 1² 2² ... 49²`), atom count exactly 50 — no corruption under real concurrent load |

This is rigorous, not anecdotal: the crash/no-crash contrast is isolated
to specifically `:collect-safe` via the bogus-flag control, and the
concurrent stress test rules out "worked once by luck."

**Rule, now proven rather than assumed: any generated `foreign-callable`
that might ever be invoked from a thread other than the one that created
it — which includes essentially any real-world async runtime, thread
pool, or C library with internal worker-thread dispatch — MUST use
`:collect-safe`. A bare `foreign-callable` is a correctness bug waiting
for the first callback that happens to fire off-thread, not a
performance-only choice.**

## What this resolves, and what it doesn't

- **Resolves** the threading-safety half of the egui-closure concern
  raised at the start of this whole project: cross-thread and concurrent
  re-entrancy into Jolt via a callback is safe, given `:collect-safe`.
- **Does not resolve** egui's actual blocker — that was never about
  threading. `impl FnOnce(&mut Ui)` can't be expressed in a C ABI at all;
  no calling-convention flag changes what types are representable at the
  boundary. This finding makes egui's Design B (thin per-widget bindings)
  *safe if built*, not *buildable*.
- **Directly informs Diplomat callback support** (see below): the
  generated binding must use `:collect-safe` unconditionally, never bare.
- **Confirms idea #5 from the original interop recap** (async bridging —
  a Rust future completing on an arbitrary `tokio` worker thread,
  invoking a Jolt promise/channel) rests on a real, now-proven-safe
  foundation rather than an assumption.

## The real `DiplomatCallback` ABI

Added `Thingy::apply_callback(&self, f: impl Fn(u8) -> u8) -> u8` to the
spike crate. Diplomat's C backend genuinely implements callback lowering
(confirmed in `diplomat-tool`'s own source, `c/ty.rs`, not assumed), and
the real generated header is:

```c
typedef struct DiplomatCallback_Thingy_apply_callback_f {
    const void* data;
    uint8_t (*run_callback)(const void*, uint8_t);
    void (*destructor)(const void*);
} DiplomatCallback_Thingy_apply_callback_f;

uint8_t Thingy_apply_callback(const Thingy* self, DiplomatCallback_Thingy_apply_callback_f f_cb_wrap);
```

Passed **by value** — same struct-by-value rule as everywhere else in
this project applies: never `defcfn` it directly, always shim, decomposed
to three scalars (`data`, `run_callback`, `destructor`).

## Destructor lifecycle — proven, not assumed

The open design question was resource cleanup: `foreign-callable`
allocates a trampoline that must eventually be freed via `free-callable`,
and the natural place to do that is the callback's own `destructor`
(called by Rust exactly once, when it's done with the closure). But that
requires the destructor to reference the *other* trampoline (`run-cb`),
and potentially itself — a self-referential construction problem, solved
with an `atom` populated after both trampolines exist:

```clojure
(let [state (atom nil)
      run-cb (ffi/foreign-callable
              (fn [_data x] (f x))
              [:pointer :uint8] :uint8 :collect-safe)
      destructor (ffi/foreign-callable
                  (fn [_data]
                    (let [{:keys [run-cb destructor]} @state]
                      (ffi/free-callable run-cb)
                      (ffi/free-callable destructor)))
                  [:pointer] :void :collect-safe)]
  (reset! state {:run-cb run-cb :destructor destructor})
  (c-apply-callback (:ptr self) ffi/null run-cb destructor))
```

Run for real: `Thingy(10).apply_callback(|x| x + 5)` →

```
:destructor-fired
:result 15
```

Two things proven by this single run, not assumed:

1. **The closure genuinely executed in Rust and returned correctly** —
   `15 = 10 + 5`.
2. **The destructor fires synchronously, before the C call returns** —
   `:destructor-fired` printed *before* `:result`. This matches Rust
   ownership semantics for a non-escaping `impl Fn` parameter (the
   generated wrapper is dropped at the end of `apply_callback`'s stack
   frame, before control returns to the caller) and means, for this
   *specific, synchronous, non-stored* callback shape, cleanup timing is
   fully deterministic — no leaked trampolines, no need to guess when
   it's safe to free.

**One thing noted but not stress-tested further**: freeing the
`destructor` trampoline from *inside its own currently-executing
invocation* worked with no crash in this run. That's a legitimately
subtle self-referential pattern (freeing the memory backing the code
you're presently executing through), and it worked — but on a single
run, not under the kind of repeated/concurrent stress test the
`:collect-safe` threading claim got. Treat as "worked, reasonable
confidence," not "proven at the same rigor as the threading result."

**Open question this milestone does not answer:** a callback *stored* by
Rust for later, asynchronous invocation (registered once, fired
repeatedly, possibly after the registering Jolt call has already
returned) — the `apply_callback` shape tested here is synchronous and
single-use. A stored/repeated callback changes the destructor-timing
picture entirely (destructor fires whenever Rust eventually drops the
stored closure, not synchronously) and is a different, harder design
question than what's proven here. Flagged as future work, not
extrapolated from this result.

## Wired into the generator — real, automatic, verified

Extended `jolt-diplomat-backend` with a `Type::Callback(cb)` param arm,
generating the shim decomposition, the `foreign-callable`/`free-callable`
atom-based wiring (emitted verbatim in the shape proven above, not
factored into a shared runtime helper — the self-referential destructor
construction was only proven in this exact inline form), and the
:collect-safe marking unconditionally on both trampolines per the rule
established above.

One real Diplomat-side gap hit immediately, not a generator bug:
**`diplomat_core::hir::TypeContext::from_syn` rejected the callback
method outright** — `"Callback arguments are not supported by this
backend"` — because our hand-reconstructed `BackendAttrSupport` (built by
reading `diplomat-tool`'s `lib.rs` early in this project, since the
struct isn't part of any published example) left `callbacks: bool`
false by omission. The real `diplomat-tool c` CLI enables it internally;
ours didn't, since nothing in the struct's public API documents which
fields a given target language is "supposed" to set. Fixed by setting
`s.callbacks = true` explicitly, with the reasoning recorded in the code
rather than left silent.

Generated output for `Thingy::apply_callback(&self, f: impl Fn(u8) -> u8) -> u8`,
unedited:

```clojure
(ffi/defcfn ^:private c-apply-callback "jolt_Thingy_apply_callback" [:pointer :pointer :pointer :pointer] :uint8)
(defn apply-callback [self f]
  (let [f-state (atom nil)
        f-run-cb (ffi/foreign-callable
                  (fn [_data a0] (f a0))
                  [:pointer :uint8] :uint8 :collect-safe)
        f-destructor (ffi/foreign-callable
                      (fn [_data]
                        (let [{:keys [run-cb destructor]} @f-state]
                          (ffi/free-callable run-cb)
                          (ffi/free-callable destructor)))
                      [:pointer] :void :collect-safe)]
    (reset! f-state {:run-cb f-run-cb :destructor f-destructor})
    (c-apply-callback (:ptr self) ffi/null f-run-cb f-destructor)))
```

Compiled, linked against the real static lib, run through real Jolt:

```
:apply-callback 15
```

`10 + 5 = 15`, correct, and every one of the eleven previously-verified
output lines from earlier milestones still passes — zero regressions.

## Final status

All five documented gaps from Milestone 5 are closed: non-`u8` slices,
enum-valued struct fields, nested structs, opaque-returning-opaque, and
now callbacks — the one gap that turned out to need genuine new design
(the destructor lifecycle question) rather than a mechanical extension of
existing machinery, and it resolved into a real, working, verified
pattern. Scoped explicitly to synchronous, non-stored, primitive-typed
callbacks (per the open question at the end of the threading section
above) — a stored/repeatedly-invoked callback remains unproven and would
need its own investigation before relying on this generator for that
shape.
