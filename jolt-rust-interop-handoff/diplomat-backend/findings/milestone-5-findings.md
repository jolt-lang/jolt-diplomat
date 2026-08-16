# Milestone 5 findings — the real generator, working end to end, 2026-08-15

## What got built

A real Rust program (`jolt-diplomat-backend/`) using `diplomat_core`'s
actual HIR API — `syn_inline_mod::parse_and_inline_modules`,
`hir::BasicAttributeValidator`, `hir::TypeContext::from_syn` — the exact
same entry point `diplomat-tool`'s own binary uses (read directly from its
source, not guessed). It walks `TypeContext::all_types()` and emits both
`.clj` bindings and a C shim automatically, encoding every rule verified
by hand in Milestones 1-4: never `defcfn` a struct-by-value crossing
directly, always flatten to scalars in the shim, always route
`DiplomatWrite` construction through `diplomat_simple_write` rather than
hand-assembling it.

## The debugging arc — every bug was real, and every one was caught by
   actually running the output, not by inspection

This is the important part. Four real bugs, in order found:

1. **`try-create` exposed `out` as a caller parameter and passed it twice**
   to the shim call (arity mismatch against the shim's declared 3-arg
   signature). Root cause: one list (`clj_arg_names`) was being reused for
   both "what the shim's `defcfn` type signature needs" and "what the
   public Clojure API should expose to callers" — two genuinely different
   things that had been conflated. Fixed by splitting into `arg_specs`
   (shim-call-shaped) and `public_params` (caller-shaped), built
   independently.

2. **`(c-value self)` passed the whole `Thingy` record to a `:pointer`
   FFI param**, instead of extracting `(:ptr self)`. Caught by the very
   first real Jolt run: `invalid foreign-procedure argument` on a
   `chez-jrec` (record) value where a bare pointer was expected. The
   hand-written version had this right (`(:ptr this)` everywhere);
   the generator's `self` handling was never taught to do the same
   extraction. One-line fix, `call_expr: "(:ptr self)"` instead of
   `"self"`.

3. **`jolt_diplomat_simple_write` didn't exist in the generated shim at
   all.** It's crate-level runtime support (needed once, whenever any
   method has a `Write` success type), not a per-type binding — the
   per-opaque-type generation loop had no reason to ever emit it, and I'd
   only written it by hand in the earlier spike, not taught the generator
   to emit it. Fixed by adding it to the shim's crate-level boilerplate,
   emitted unconditionally alongside the `#include`s.

4. **The subtlest one: `(:verbose opts)` passed Clojure `false` directly
   into an `:int` FFI parameter.** `invalid foreign-procedure argument #f`
   — Jolt's `defcfn` does not coerce Clojure booleans to 0/1 for `:int`
   params; it expects an actual number and errors on `#f`. The
   hand-written `describe` had `(if verbose 1 0)`; the generator's
   struct-field-flattening never re-derived this rule. Confirmed via
   careful bisection: a manually-typed version of the exact same code
   worked when I passed `0`/`1` literally and failed identically to the
   real bug the moment I substituted `(:verbose opts)` for the literal —
   isolating the boolean-coercion issue precisely rather than guessing.
   Fixed by tracking `is_bool` per flattened field in
   `flatten_struct_fields` and wrapping the call expression in
   `(if field-access 1 0)` whenever the underlying Rust field is `bool`.

None of these four were visible from reading the generator's own source —
each required actually compiling the shim, linking it against the real
static lib, and running the generated `.clj` through real Jolt to surface.
Bug #4 in particular is the kind of thing no amount of code review would
have caught; it only appeared once a `false` value (not just `true`)
flowed through the boolean field, which the `describe-terse` call
(`{:verbose false ...}`) exercises and `describe-verbose` does not.

## Final verified output

Running the actual generator against the actual spike crate, with zero
hand-editing of its output, then compiling and linking that output, then
running it through real Jolt v0.7.13:

```
:value 42
:describe-terse 42
:describe-verbose Thingy(value=42, scale=2.5)
:sum-with 48
:error-path-ok #:diplomat{:error 0}
```

Identical to the hand-written, doc-cross-checked Milestone 4 result — the
generator now reproduces by machine exactly what was verified by hand.

## Known, honest scope limits of this generator (not bugs — deliberate
   cuts, documented rather than hidden)

- **Primitive types**: `bool`, `u8`, `i32`, `u32`, `f64` only. Extending
  the `prim_to_jolt_and_c` match to the rest of `PrimitiveType`/`IntType`
  is mechanical, not a design question — same pattern, more arms.
- **Struct fields**: flat primitives only; a struct containing another
  struct, an opaque, or a slice would panic (by design — loud failure
  over silent wrong output). Real crates will hit this; extending
  `flatten_struct_fields` to recurse is the natural next increment.
- **Slice-of-primitive params take a raw Clojure seq**, marshaled via the
  new `with-u8-buffer` macro — only `u8` is wired (`DiplomatU8View`);
  other element widths need their own `DiplomatXView` C type name and a
  width-generic buffer-marshaling macro in `diplomat.runtime`.
- **No enum-valued struct fields, no nested opaques, no callbacks, no
  iterators** — none of these appear in the spike crate, so none are
  implemented; each would need real design (especially callbacks, which
  intersect with the `foreign-callable`/`:collect-safe` questions from
  the very first turns of this project) rather than a mechanical
  extension.
- **`ArgSpec.public_name` field is now dead** — superseded by building
  `public_params` directly from `m.params`, which turned out to be
  simpler and more correct for the struct-collapses-to-one-map case.
  Harmless dead code, left in with a compiler warning rather than
  silently deleted, since it documents an abandoned design path.

## Where this leaves the project

The backend is no longer a design or a hand-verified single case — it is
a working code generator, proven against the same real toolchain (real
`diplomat_core`, real `gcc`, real Jolt v0.7.13) as every other verified
piece of this project, with its own real bugs found and fixed the same
way everything else in this project was: by running it and reading what
actually happened, not by reasoning about what should happen.

Next natural step, if continued: point this same generator at a second,
larger Rust crate (real ICU4X was the original stretch goal from the very
first plan) to find the next layer of scope gaps — nested structs,
non-`u8` slices, enum-valued fields — the same way this session found
these four.

## Extension test: a non-`u8` slice, to probe the documented scope gap

Milestone 5 explicitly flagged "only `u8` is wired" as a known gap. Tested
it directly rather than leaving it as a documented limitation: added
`Thingy::sum_with_i32(&self, others: &[i32]) -> i64` to the spike crate,
regenerated the real C header first (`DiplomatI32View`, confirmed —
Diplomat's `MAKE_SLICES_AND_OPTIONS` macro really does emit one named view
type per primitive, exactly as `diplomat_runtime.h` implied), then
generalized the generator:

- `prim_to_diplomat_view_suffix` maps any covered `PrimitiveType` to its
  real `DiplomatXView` name, replacing the hardcoded `DiplomatU8View`.
- `with-primitive-buffer` in `diplomat.runtime` generalizes
  `with-u8-buffer` to any element type via `ffi/sizeof`, kept alongside
  the original rather than replacing it.
- Added `i64`/`u64` to the primitive type table (`:int64`/`:uint64`,
  confirmed real keywords from `native-interop.html`'s type table).

Result: **correct on the first real run**, no new bugs — `-158` for
`42 + (-100 + 200 - 300)`, verified through the full real chain (Diplomat
→ generated shim → compiled → linked against the real static lib → real
Jolt v0.7.13). The earlier four bugs were all found on the *first* real
target the generator was pointed at; extending it to a second, different
shape within that same crate produced clean output immediately —
reasonable evidence the `arg_specs`/`public_params`/`buffer_wraps`
architecture from the bug-fixing pass generalizes rather than being
Thingy-specific patches in disguise.

## Second extension test: enum-valued struct fields

Also flagged as a documented gap. Added `Mode` enum (`Terse`/`Verbose`/
`Debug`) and `ThingyOptions2 { mode: Mode, scale: f64 }`, checked the real
header first (`Mode mode;` — the enum's own C type name as the field
type, `Mode_Terse=0` etc., confirming the same pattern already handled
for `ThingyError`), then extended the generator:

- `flatten_struct_fields` now takes `tcx` and resolves `Type::Enum` fields
  via `EnumPath.tcx_id` (a plain field, simpler than `StructPath`'s `id()`
  accessor — found by reading `diplomat_core`'s source directly rather
  than assuming the same pattern applied uniformly).
- Enum fields collect into a new `extra_requires: BTreeSet<String>`,
  threaded through `gen_method` and consumed by `gen_opaque_clj` to emit
  a correct cross-namespace `:require` (`[diplomat.mode :as mode]`) —
  required because unlike bool coercion, an enum field needs a lookup
  into a *different* generated file's table, which the single-file
  method-by-method generation loop had no prior reason to know about.
- The call expression becomes `(mode/kw->int (:mode opts))`, reusing the
  already-existing `gen_enum_clj`/`kw->int` machinery from `ThingyError`
  — no new runtime support needed, just correct wiring.

Result: **correct on the first real run**, all three enum variants
(`:terse`/`:verbose`/`:debug`) exercised and producing exactly the
expected strings, verified through the full real chain. Two documented
gaps down (non-`u8` slices, enum-valued struct fields), both resolved
cleanly on the first attempt once the underlying `arg_specs`/
`public_params`/cross-namespace-`require` machinery existed — the
remaining gaps (nested structs, opaque-returning-opaque, callbacks) are
the ones most likely to need real new design rather than mechanical
extension, callbacks especially.

## Third extension test: nested structs

Added `Point { x: f64, y: f64 }` and `ThingyOptions3 { point: Point, scale:
f64 }`, checked the real header first (Diplomat accepted the nesting with
no complaint — `Point point;`, `#include "Point.d.h"`, no special
attribute needed), then replaced the entire flattening approach rather
than special-casing nesting on top of the old one-level
`flatten_struct_fields`:

- `FieldShape` — a small tree (`Prim` / `EnumField` / `Nested`) that
  `resolve_field_shape` builds recursively from the HIR. This turned out
  to *subsume* the bool-coercion and enum-lookup special cases cleanly:
  they're just two of `FieldShape`'s leaf variants, and `Nested` recurses
  into the same tree rather than needing its own logic.
- `flatten_leaves` walks the tree once, producing one flat C parameter
  per leaf with a correctly-nested Clojure access expression
  (`(:x (:point opts))` for a field two levels deep) — a single recursive
  function replacing what would otherwise have needed hand-written
  two-level, three-level, etc. cases.
- `build_c_literal` is the exact inverse, rebuilding the (possibly
  nested) C struct literal the shim needs from the same tree.
- One real API-shape correction along the way: `StructDef::fields` is
  generic over `TyPosition` and defaults to `Everywhere`, not
  `InputOnly` — `resolve_field_shape` needed to become generic over `P:
  TyPosition` rather than hardcoding `InputOnly`, caught immediately by
  the compiler (not a runtime bug, a real type-checked correction).

Result: **correct on the first real run**, nested field access and nested
C struct construction both exactly right —
`Thingy(value=99, point=(1.5, -2.25), scale=3)` — with zero regressions
on any of the six previously-verified lines. Three documented gaps down
(non-`u8` slices, enum-valued fields, nested structs), all resolved
cleanly, and the nested-struct fix actually *simplified* the codebase
(one recursive tree instead of three separate special cases for
primitive/bool/enum fields). The pattern holds: each real extension is
finding real gaps, but the architecture is absorbing them rather than
accumulating patches. Remaining: opaque-returning-opaque (likely
mechanical, same shape as the already-solved try-create pattern) and
callbacks (genuinely new design territory, not mechanical).

## Fourth extension test: opaque-returning-opaque

Added a second opaque type, `Doubled(u16)`, and `Thingy::double(&self) ->
Box<Doubled>`. Checked the real header first: `Doubled* Thingy_double(const
Thingy* self);` — a plain pointer return, no struct-by-value crossing at
all, so this method needed no shim, just an extension to the "direct
call" path's return-type handling (previously only `Unit`/`Primitive`).

- Added `hir::OutType::Opaque(_) => ":pointer"` to the direct-return match.
- The generated body wraps the raw pointer in the *target* opaque's own
  record constructor — `(doubled/->Doubled (c-double ...) false)` — using
  the same `extra_requires` cross-namespace mechanism built for enum
  fields, now reused for a second, structurally different purpose
  (qualifying a defrecord constructor, not a lookup table).
- `OpaquePath.tcx_id` is a plain `OpaqueId` field (same shape as
  `EnumPath.tcx_id`, confirmed rather than assumed after the earlier
  `StructPath::id()` vs `EnumPath.tcx_id` inconsistency taught not to
  assume API uniformity across path types).

**A genuinely new, real gap surfaced along the way, unrelated to
opaque-returning-opaque itself**: `Doubled::value` returns `u16`, and
**jolt.ffi has no 16-bit integer type at all** — confirmed directly
(`:uint16`, `:u16`, `:short` all fail `unknown foreign type`), not merely
undocumented. Fixed by width-widening to the native `:uint`/`:int`: since
standard C calling convention (SysV x86-64, matched by AAPCS64) zero/
sign-extends narrow return values into the full register per the
underlying type's signedness, reading a `uint16_t`-returning function's
result as jolt's `:uint` yields the exact correct value for every
representable `u16` — a correct widening, not an approximation, and now
documented as such directly in `prim_to_jolt_and_c` rather than left as a
silent gap.

Result: **correct on the first real run** (`21 * 2 = 42`), all nine
previously-verified output lines still passing, zero regressions. Four
documented gaps down. The u16 finding is a useful reminder that "point
the generator at a new shape" keeps surfacing real facts about jolt.ffi
itself, not just about the generator — this project's actual value has
been as much about mapping jolt's real FFI surface precisely as about
building the generator.
