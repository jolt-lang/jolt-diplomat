# Milestone 2 status — 2026-08-15 (continued from milestone-1-findings.md)

## Struct-by-value-return question: unresolved by search, resolved by decision

Web search for jolt.ffi struct-by-value-return support found nothing —
neither confirming nor ruling it out. One adjacent, real data point:
`coffi` (a mature Clojure/JVM FFI library) documents actual crashes on
M1 Macs returning structs by value from native code, an acknowledged
upstream Panama issue — struct-by-value returns are a genuinely flaky
corner of FFI design broadly, across implementations, not a Jolt-specific
worry.

**Decision, not a guess: default to the C-shim workaround unconditionally**,
regardless of whether jolt.ffi turns out to support struct-by-value
returns. Rationale: even if supported, a shim is strictly safer (converts
an ABI edge case into the well-trodden "struct in parameter position via
pointer" case jolt.ffi's docs actually cover), costs one generated .c file
and a `cc` invocation per Result-returning function, and removes the
question from the critical path entirely. Revisit only if shim-generation
proves to be a real maintenance burden at scale (unlikely — it's
templated, one shape, one C statement per Result-returning function).

## This decision is now VERIFIED, not just decided — full chain tested

Built the actual verification, not a paper design:

1. `offset-gen/` — a real Rust tool computing struct layout via pointer
   arithmetic (`addr_of!`, stable since 1.51 — avoided `offset_of!` since
   this environment's rustc is 1.75 and that macro needs 1.77+). Run
   against the REAL struct definitions from `out-c-real/`, not guesses.
   Output: `jolt-runtime/diplomat/generated_offsets.clj`.

   **Confirms finding #3 was real**: `DiplomatWrite` is 56 bytes (hand
   copy assumed 48), `flush`/`grow` sit at offsets 40/48 (hand copy
   assumed 32/40). `diplomat.runtime`'s `writeable-capture` is now wired
   to these generated offsets instead of hand-maintained constants, and
   explicitly writes `grow_failed` (offset 32, previously unaccounted for
   entirely).

2. `jolt-backend/shim-verified/thingy_shim.c` — the generated-shim
   pattern from the Result decision above, hand-written as the template,
   compiled with `gcc -c` against the real `out-c-real/` headers, and
   **linked successfully against `libdiplomat_jolt_spike.a`** (the actual
   static lib built in Milestone 1).

3. `jolt-backend/shim-verified/test_shim.c` — a full C harness exercising
   every piece end to end: the shim's success path, the shim's error path,
   `Thingy_value`, `Thingy_sum_with` (slice), `Thingy_describe` (struct
   param + `DiplomatWrite` string return via `diplomat_simple_write`),
   `Thingy_destroy`. **Compiled and run for real:**

   ```
   success case: is_ok=1
     value(): 42
     sum_with([1,2,3]): 48
     describe(verbose): Thingy(value=42, scale=2.5)
   error case: is_ok=0 err=0
   ```

   `42` round-tripped through the fallible constructor, `48 = 42+1+2+3`
   confirms the slice marshaling, the writeable string is byte-exact, and
   the error path correctly reports `is_ok=0`.

## What this proves, precisely

Every C-side mechanism the Jolt backend needs to generate against is now
proven correct in this environment, not assumed:
- opaque construction/destruction
- the Result-via-shim pattern, compiled and linked against a real static lib
- struct-by-value parameter passing
- slice passing
- `DiplomatWrite` string return, using the corrected, generated offsets

What's still NOT proven — because it requires the actual Jolt toolchain,
which isn't available in this sandbox: whether `jolt.ffi`'s `defcfn` can
call into `libdiplomat_jolt_spike.a`/`.so` at all (the C ABI side is
solid; the Chez/Jolt FFI-declaration side is unverified). That's the
literal next gap, and it can't be closed further without a real Jolt
install.

## Updated next step

The C-side half of Milestone 2 is done and verified. The remaining half —
writing real Jolt `.clj` that calls these compiled artifacts — needs a
working `jolt` binary, which this environment doesn't have. Two honest
options:
1. Get `jolt` installed here (check jolt-lang.net's install instructions
   for a Linux binary or build-from-source path) and finish Milestone 2
   for real, the same way Milestone 1 got finished for real.
2. Hand this artifact set (headers, shim, static lib, generated offsets)
   to a machine that already has Jolt installed, and verify the `.clj`
   side there.
