# Handoff: Jolt ↔ Rust interop via Diplomat

Everything in this package was built and verified inside a sandboxed
environment capped at rustc 1.75 with restricted network access. That cap
blocked the two things that matter most for continuing this properly:
current `diplomat-tool`/`diplomat_core` (latest is `0.14.1`; this project
was built against `0.10.0` — four minor versions of drift), and `icu_capi`
2.0 (needs rustc 1.81+). This doc is everything needed to pick the project
up on a real machine and close both gaps.

## What's actually proven vs. what needs re-verification

Read this section before touching anything — it tells you what's safe to
build on and what to re-check first.

### Proven, and will NOT change with a Diplomat upgrade

These are properties of **Jolt itself** (v0.7.13), verified directly
against the running binary, independent of Diplomat's version:

- `jolt.ffi` has **no struct-by-value** support anywhere — confirmed via
  `unknown foreign type :struct` and `ClassCastException` on every
  composite type literal tried, in both parameter and return position.
  Every crossing must be shim-decomposed to scalars. (`findings/
  milestone-3-findings.md`, `milestone-4-findings.md`)
- The real type-keyword table: `:int :uint :long :ulong :int64 :uint64
  :size_t :ssize_t :iptr :uptr :double :float :char :uint8 :pointer
  :string :void`. No `:bool`, no fixed-width 16-bit types (`:uint16`
  doesn't exist — widen to `:uint`/`:int` instead, see
  `milestone-6-callbacks-findings.md`).
- `ffi/null` is a **value** (`0`), not a zero-arg function.
- **`:collect-safe` is required, not optional**, on any `foreign-callable`
  that might ever fire from a thread other than the one that created it.
  Without it: confirmed hard `SIGABRT` (`nonrecoverable invalid memory
  reference`) on genuine cross-thread invocation. With it: proven correct
  under 50 concurrent OS threads. (`milestone-6-callbacks-findings.md` —
  this is the single most load-bearing finding in the whole project)
- `DiplomatWrite` must always be constructed via the library's own
  `diplomat_simple_write`, never hand-assembled — a hand-built struct
  with null `flush`/`grow` function pointers crashes, confirmed directly.

### Will need re-verification against current Diplomat

- **Every `diplomat_core` HIR API call in `jolt-diplomat-backend/src/
  main.rs`.** Specifically: `TypeContext::from_syn`'s signature,
  `BasicAttributeValidator::new`, `resolve_opaque`/`resolve_enum`/
  `resolve_struct`, `EnumPath.tcx_id` / `OpaquePath.tcx_id` (plain fields)
  vs. `StructPath.id()` (a method — these were inconsistent across path
  types in 0.10.0, confirmed by trial and error; don't assume uniformity
  in whatever version you land on either). `BackendAttrSupport`'s full
  field list, especially `callbacks: bool` — defaults `false`, and
  Diplomat's own lowering silently rejects callback methods with no
  hint about which flag fixes it until you set `s.callbacks = true`
  (`milestone-6-callbacks-findings.md`).
- **The `DiplomatWriteable`→`DiplomatWrite` rename's current status.**
  Confirmed as a real, breaking incompatibility for 0.8-era source against
  0.10.0's parser (`milestone-7-real-crate-findings.md`). Whether current
  Diplomat still hard-fails on the old name, or has since added
  backward-compat, is unknown and worth checking early — it directly
  determines whether the "rename-and-retry" compatibility technique is
  still needed for older target crates.
- **Whether `icu_capi` 2.0 (matched-version, unlike the deliberate 1.5.1/
  0.10.0 mismatch tested here) hits genuinely new gaps.** Milestone 7's
  findings were mostly version-skew noise, not real generator gaps — a
  matched-version run is the first chance to see what's *actually* still
  missing.

### Architecture that should transfer with minimal changes

The design decisions are Diplomat-version-agnostic in principle, even if
the exact API calls need updating:

- Shim-based decomposition of every struct-by-value crossing (param or
  return), always — never rely on ABI register-packing coincidences
  (confirmed real for `DiplomatU8View` on x86-64 SysV, and confirmed
  fragile — don't generate code that depends on it).
- The `FieldShape` recursive tree (`Prim`/`EnumField`/`Nested`) for
  arbitrary struct nesting — this actually *simplified* when nesting was
  added (Milestone 5), a good sign the abstraction is sound.
- Offset-table generation via pointer arithmetic (`addr_of!`, not
  `offset_of!` — the latter needs rustc 1.77+; on a real machine with a
  current toolchain you can switch to `offset_of!` if you want, it's
  cleaner).
- The callback destructor lifecycle: an `atom` populated after both
  trampolines exist, so the destructor can free itself and its sibling
  when Rust invokes it — proven correct for synchronous, non-stored,
  single-invocation callbacks. **Not proven** for a callback Rust stores
  and fires later/repeatedly — flagged as real future work, don't assume
  it generalizes without testing.

## Step-by-step upgrade plan

1. **Install a current Rust toolchain.** `rustup install stable` (this
   sandbox couldn't reach `static.rust-lang.org` — a real machine won't
   have that problem). Confirm `rustc --version` is 1.81+.

2. **Regression-check against the spike crate first, before touching real
   ICU4X.** This is the fast, cheap check that the upgrade didn't break
   anything:
   ```
   cd diplomat-backend/jolt-diplomat-backend
   # bump Cargo.toml: diplomat_core = "0.14"  (or whatever's current)
   cargo build --release
   # fix compile errors — expect them in the API surfaces listed above
   ./target/release/jolt-diplomat-backend ../spike-crate/src/lib.rs /tmp/out
   # diff /tmp/out against generated-output-verified/ — should match
   # structurally (exact formatting may drift, that's fine)
   ```
   Then rebuild the spike crate itself (`cd ../spike-crate && cargo
   build --release`), compile the shim, and re-run `verified-project/
   src/demo.clj` through Jolt. All twelve output lines
   (`:value 42` through `:apply-callback 15`) should reproduce exactly —
   see `verified-project/src/demo.clj` for the full expected output in
   the findings docs.

3. **Install current Jolt.** Same install script, no version pin this
   time:
   ```
   curl -sL https://raw.githubusercontent.com/jolt-lang/jolt/main/install | bash
   ```
   Check `jolt --version` — if it's newer than `v0.7.13`, re-run the
   probes in `findings/milestone-3-findings.md` and `milestone-6-
   callbacks-findings.md` (the `:struct` type rejection, the type-keyword
   table, the `:collect-safe` cross-thread test) before trusting anything
   downstream of them. Jolt's docs describe the project as moving fast;
   don't assume these are still true without re-checking, even though
   they're unlikely to have regressed.

4. **Point the generator at real, matched-version ICU4X.**
   ```
   cargo new --lib icu-probe && cd icu-probe
   cargo add icu_capi   # whatever's current, no version pin needed now
   ```
   This time, try the **full crate** first (`-e src/lib.rs`), not a
   single isolated module — with matched versions, the `list.rs`
   slice-of-slice and `DiplomatWriteable` issues from Milestone 7 may
   simply not occur. Whatever *does* fail is the real, current finding.

5. **Fix what breaks, document what you find**, in the same style as
   `findings/milestone-1..7`: what was tried, what the real error was,
   what the actual fix was, and what got verified end-to-end afterward
   (compiled shim, linked, run through real Jolt, real output). That
   discipline — nothing goes in as "should work," everything goes in as
   "did this, saw this" — is the single thing that made this project's
   findings trustworthy. Keep it.

## What's in this package

```
diplomat-backend/
  PLAN.md                        original spike plan (milestones 1-9)
  findings/                      milestone-1 through milestone-7, in order —
                                  read these in order, they build on each other
  jolt-diplomat-backend/          the actual generator (Rust, uses diplomat_core's
                                  real HIR — start here for the upgrade)
  jolt-runtime/diplomat/          hand-verified Jolt-side runtime support
                                  (runtime.clj — guardians/lifecycle, Result
                                  unwrapping, writeable-string capture,
                                  struct offset marshaling, callback wiring)
  spike-crate/                    the Rust test crate every finding was
                                  verified against — exercises every shape:
                                  opaque+fallible-ctor, slices (u8 and i32),
                                  enum-valued struct fields, nested structs,
                                  opaque-returning-opaque, callbacks
  generated-output-verified/      the generator's real output for spike-crate,
                                  confirmed running correctly through real Jolt
  out-c-real/                     real Diplomat-generated C headers for
                                  spike-crate (ground truth for what Diplomat
                                  actually emits, vs. documentation)
  verified-project/               a real minimal Jolt project (deps.edn +
                                  src/) that runs the full generated bindings
                                  end to end — this is your regression test
  offset-gen/                     standalone tool computing real struct
                                  layout via pointer arithmetic
  callback-test/                  the C shims used to prove :collect-safe's
                                  threading behavior (same-thread, cross-thread,
                                  concurrent stress test)
  real-crate-test/                the one clean real-ICU4X success (errors.rs /
                                  ICU4XError, 62 real variants, real
                                  discriminants) plus the source of the
                                  full-crate compatibility findings
  superseded-early-skeleton/       the original hand-written design sketch from
                                  before any of this was verified — kept for
                                  historical trace only, do not build from this

gtk-glimmer-plan/                 separate track: a plan + code sketches for
                                  deepening Jolt's existing GTK4 backend
                                  (glimmer) — UI-thread marshalling fix, CSS
                                  theming, widget registry widening, an
                                  opt-in libadwaita package. Unrelated to the
                                  Diplomat work; not blocked by this handoff.
```

## Polars bridge — separate track, findings in milestone-8

A design analysis for Jolt↔Polars interop (via the Arrow C Data
Interface, not Polars' own generics-heavy Rust API) plus real testing of
the one previously-open risk — a callback stored and invoked repeatedly
from a genuinely different thread, standing in for `rayon`'s parallel
execution. Real `polars` itself could not be built in this sandbox (same
MSRV wall as `icu_capi` 2.0, confirmed after 8 rounds of dependency
pinning — see `findings/milestone-8-polars-findings.md`), so this used a
minimal, honestly-labeled stand-in crate (`polars-analysis/polars-bridge-
stub/`) with the identical FFI shape. Two real findings worth knowing
before writing the actual bridge: Diplomat's callback macro rejects
multi-trait-bound closures (`impl Fn(...) + Sync` fails outright — work
around it with an unsafe `Send`/`Sync` newtype *inside* the bridge, not
in the exposed signature), and that workaround has a sharp edge in Rust
2021's disjoint closure capture that will silently defeat it unless you
know to force whole-value capture. Read `milestone-8-polars-findings.md`
before starting the real bridge crate — both findings will otherwise cost
real debugging time.

## Known minor gap, worth fixing early

`to_kebab` in the generator splits acronym-plus-digit runs into single
letters — `ICU4XError` becomes `i-c-u4-x-error` instead of the natural
`icu4x-error`. Cosmetic, not a correctness bug (valid Clojure symbol
either way), but every real ICU4X type name will hit it, so it's worth a
five-minute fix (treat a run of capitals, optionally followed by a digit,
as one token) before generating anything you intend to actually read.

## Suggested first PR

Steps 1-2 above (toolchain bump, spike-crate regression check) — small,
fast, and it tells you immediately how much HIR API drift you're dealing
with before committing to the larger ICU4X matched-version run.
