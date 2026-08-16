# Milestone 7 findings — stress-testing against real `icu_capi` source,
  2026-08-16

Goal: find gaps a synthetic spike crate can't, per the plan at the end of
Milestone 6. Used `icu_capi` — the actual reference project Diplomat is
designed against, and the original stretch goal named all the way back in
the first interop plan of this whole effort.

## Toolchain wall, worked around by not needing it

`icu_capi` 2.0's dependency graph needs rustc 1.81+; this sandbox is
capped at 1.75 (apt-only, `rustup` unreachable — `static.rust-lang.org`
isn't on the allowed domain list, confirmed via a direct 403). Pinning
older transitive deps (`time-core`, `litemap`, `calendrical_calculations`)
turned into whack-a-mole against an ecosystem that's broadly moved past
1.75's MSRV.

**Worked around by recognizing what `diplomat-tool`/our generator actually
need**: HIR lowering parses raw `syn` source *before* macro expansion —
it never invokes `rustc`, never needs the crate's dependencies to
resolve, and doesn't care whether `cargo build` would succeed. Extracted
`icu_capi`'s `.crate` source directly from the registry cache
(`tar xzf .../icu_capi-1.5.1.crate`) and ran `diplomat-tool`/our backend
against the raw `.rs` files. This is a generally useful technique for
"is my Diplomat backend compatible with this source" that has nothing to
do with whether it also compiles.

## Even the official `diplomat-tool c` backend cannot process the full
   real crate against our pinned `diplomat_core` 0.10.0

Not a bug in anything we built — a genuine cross-version compatibility
gap between `icu_capi` 1.5.1 (written against `diplomat`/`diplomat-runtime`
0.8) and the `diplomat_core` 0.10.0 this whole project is built on.
Found, in order, chasing `-e src/lib.rs` (the full 44-module crate):

1. **`list.rs`**: `&[&str]` (slice-of-slice) rejected by `diplomat_core`'s
   own AST layer — *"only supported with DiplomatRuntime slice types
   (DiplomatStrSlice, DiplomatStr16Slice, DiplomatUtf8StrSlice)"*. A real
   pattern our generator has never seen (nor was asked to handle).
2. **18 of 44 files** reference a bare `DiplomatWriteable` with **no
   visible import at all** — relying on some ambient resolution that only
   the real macro-expanded compilation provides, which `syn`-based
   pre-expansion parsing structurally cannot see. This is a genuine
   architectural limit shared by *any* Diplomat backend built this way,
   not specific to ours.
3. **The real root cause, confirmed empirically, not just imports**:
   adding explicit `use diplomat_runtime::DiplomatWriteable;` imports
   didn't fix it — the next error was *"could not resolve symbol
   diplomat_runtime"* itself. `diplomat_core` 0.10.0's parser only
   recognizes a **hardcoded table of current-name runtime types**
   (`DiplomatWrite`, not the pre-rename `DiplomatWriteable`) — the same
   rename discovered empirically back in Milestone 1, now confirmed to be
   a real, breaking, structural incompatibility for any 0.8-era source,
   not a documentation nuance. **The actual fix, verified by trying it**:
   a global `DiplomatWriteable` → `DiplomatWrite` rename across the
   source. That got the tool past the panic entirely, onto a clean,
   structured (non-crashing) diagnostic instead.
4. **`fallbacker.rs`**: `ICU4XLocaleFallbackConfig<'a>` has a struct field
   typed `&'a DiplomatStr` — rejected with a **proper, non-panicking
   error message**: *"Found FFI-unsafe type &'a DiplomatStr in struct
   field ... consider using DiplomatStrSlice<'a>"*. A real pattern —
   borrowed slice types in struct *field* position, not just method
   parameters — that neither our generator nor the synthetic spike ever
   exercised. Worth noting this is Diplomat's own validation working
   correctly, not a crash.

Excluding `fallbacker.rs` broke a *different* module's cross-reference —
the 44 files are genuinely interconnected, and chasing every remaining
compatibility gap module-by-module started testing "how compatible is
`icu_capi` 1.5.1 with `diplomat_core` 0.10.0" rather than "what does our
generator do differently against real code." Stopped there and pivoted to
isolating genuinely self-contained real modules instead.

## Real success: `errors.rs` / `ICU4XError`, our generator, zero bugs

`errors.rs` has zero `crate::` dependencies — genuinely standalone. After
the same `DiplomatWriteable`→`DiplomatWrite` fix, both real `diplomat-tool
c` and **our own `jolt-diplomat-backend`** processed it cleanly, first
try, no panics:

```
wrote i-c-u4-x-error.clj
```

This is a real, in-the-wild 62-variant enum — `ICU4XError` — with
genuinely **non-sequential discriminants**, namespaced by error category
exactly as ICU4X actually ships it:

```clojure
(def kw->int {
  :unknown-error 0
  :writeable-error 1
  :out-of-bounds-error 2
  :utf8-error 3
  :data-missing-data-key-error 256
  :data-missing-variant-error 257
  ...
  :locale-undefined-subtag-error 512
  ...
  :property-unknown-script-id-error 1024
  ...
```

`gen_enum_clj` reads `v.discriminant` directly from the HIR, not a
sequential index — confirmed correct here for the first time against
data where it actually matters. Every synthetic enum this project built
before now (`ThingyError`, `Mode`) happened to have sequential
discriminants, so this real crate is the first genuine test of that code
path, and it passed clean.

## One real, minor gap found: acronym handling in `to_kebab`

`ICU4XError` became the namespace/filename `i-c-u4-x-error` — every
capital letter treated as a new word boundary, including inside the
`ICU4X` acronym-plus-digit run. Cosmetically ugly (`icu4x-error` would be
the natural conversion) but **not a correctness bug** — a valid Clojure
symbol either way, no collisions, no breakage. Worth fixing
(`to_kebab` could special-case runs of uppercase letters as a single
token) but genuinely low priority compared to the structural findings
above; real ICU4X naming (`ICU4X`-prefixed everything) would hit this on
every single generated file, so it's worth doing before pointing the
generator at this crate again in earnest.

## Net assessment

The generator itself, on the one real code it got to run against, had
**zero bugs** — a meaningfully different outcome from every synthetic
extension in Milestone 5, where new shapes reliably surfaced real issues.
That's not evidence the generator is bulletproof; it's evidence that
`ICU4XError` didn't exercise anything genuinely new relative to
`ThingyError`/`Mode` beyond scale and real discriminant values, both of
which it handled correctly.

**What this milestone actually found** is less about our generator and
more about the ecosystem it has to operate in: real, currently-published
Diplomat-consuming crates lag behind `diplomat_core`'s current parser in
non-trivial, structural ways (the `DiplomatWriteable` rename genuinely
breaks 0.8-era source, not just cosmetically), and even upstream's own
tooling can't process such a crate without either an exact version match
or manual patching. **A real productionization of this backend would need
to either pin a specific, tested `diplomat_core` version per target crate,
or maintain a small compatibility-shim pass** (the same
rename-and-retry technique used here) for crates written against older
Diplomat releases — a genuine, concrete requirement this milestone
surfaced that no synthetic testing could have.
