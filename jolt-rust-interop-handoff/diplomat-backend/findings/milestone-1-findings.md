# Milestone 1 findings — real `diplomat-tool 0.10.0` build, 2026-08-15

Executed, not guessed: installed `diplomat-tool 0.10.0` (rustc 1.75 via apt;
`cargo install --locked` needed — unlocked resolves a `clap_lex` requiring
`edition2024`/rustc 1.88+), built `spike-crate` against real `diplomat`/
`diplomat-runtime` 0.10, ran `diplomat-tool c out-c -e src/lib.rs`, and built
both `libdiplomat_jolt_spike.a` and `.so`. Real generated headers saved in
`out-c-real/` alongside this file.

## Toolchain note (environment-specific, not Diplomat's fault)

`cargo install diplomat-tool` with no flags fails on rustc 1.75 — the
dependency resolver picks a `clap_lex` requiring rustc 1.88+/edition2024.
**Fix: always `cargo install diplomat-tool --locked`**, which uses the
crate's shipped lockfile and resolves against older, compatible dependency
versions. Worth a one-line note in the real backend's build docs so nobody
loses an hour to this.

## Confirmed correct, no changes needed

- `#[diplomat::bridge]`, `#[diplomat::opaque]`, `DiplomatWrite` (not
  `DiplomatWriteable` — naming drifted from the book across versions,
  flagged as a risk beforehand, confirmed real) all compiled clean, first
  attempt.
- Both `cdylib` and `staticlib` outputs build from one `crate-type` line —
  the `jolt build` static-link story is sound as designed.
- Slices are exactly `{ptr, len}` (`DiplomatU8View { const uint8_t* data;
  size_t len; }`) — matches the `ffi/write-array` plan with no changes.
- `Thingy_destroy(Thingy* self)` is a plain separate exported symbol —
  the guardian/`close!` lifecycle design targets the right shape.
- C enums are plain `enum` with explicit discriminants (`ThingyError_ParseError
  = 0`) — the `kw->int` static-table design holds as written.

## Found wrong — real fixes, not guesses

### 1. `#[diplomat::out]` means the opposite of what I assumed

It marks **output-only** structs. I'd put it on `ThingyOptions`, an *input*
param, and Diplomat's own lowering pass caught it immediately:

```
Lowering error in Thingy::describe: found struct in input that is
marked with #[diplomat::out]: ThingyOptions in ThingyOptions
```

Fix: removed the attribute. Plain non-opaque structs need **no attribute**
in input position.

**Backend design correction:** the offset-table codegen (originally
"Milestone 6") must trigger on *any* non-opaque struct in a parameter
position — not specifically on `#[diplomat::out]`-marked types, which mean
something narrower (output structs Rust constructs and hands back).

### 2. `Result<T, E>` is a named by-value return struct, not the
   out-pointer + tag design I built `unwrap-result!`/`c-Thingy-try-create`
   against

Real generated signature:

```c
typedef struct Thingy_try_create_result {
  union { Thingy* ok; ThingyError err; };
  bool is_ok;
} Thingy_try_create_result;

Thingy_try_create_result Thingy_try_create(DiplomatStringView s);
```

One generated struct **per call site**, returned **by value** — no
out-pointer parameter at all. `s` itself also arrives as a `DiplomatStringView
{ptr, len}` struct-by-value, not the `[:string :size_t]` two-scalar-arg
signature I wrote.

**This simplifies the call site** (no `ffi/alloc` for a result out-param)
but **raises the one real open question from this spike**: does
`jolt.ffi`'s `defcfn` support a **struct-by-value return type**? Every
example in the native-interop guide discussed structs crossing as
*parameters*; a by-value *return* wasn't covered anywhere I read. If
`defcfn` can't express it directly, the generated binding needs a small C
shim per `Result`-returning function — `Thingy_try_create_shim(..., Thingy_try_create_result* out)`
— written by the backend, that just calls the real function and writes the
result through an out-pointer, sidestepping the question. Decide this in
Milestone 2, first thing, since it changes the shape of every fallible
method binding.

### 3. `DiplomatWrite`'s layout has one more field than assumed, at the
   worst possible spot

Real struct:

```c
typedef struct DiplomatWrite {
    void* context;
    char* buf;
    size_t len;
    size_t cap;
    bool grow_failed;              // <-- not accounted for
    void (*flush)(struct DiplomatWrite*);
    bool (*grow)(struct DiplomatWrite*, size_t);
} DiplomatWrite;
```

`diplomat.runtime`'s hand-copied offsets (`O-context 0, O-buf 8, O-len 16,
O-cap 24`, `writeable-struct-size 48`) are correct up through `cap` but
wrong for everything after — `grow_failed` shifts both function-pointer
offsets, and the struct is bigger than assumed. **This is the exact
failure Milestone 6's generated-offset-table approach exists to prevent —
and it happened on the very first hand-copy attempt**, with the real
header one `cargo install` away the whole time. Strengthens rather than
changes the plan: don't hand-maintain this struct's offsets anywhere,
generate them from the real header (or via `bindgen`/`ffi_offset_of!` on
`diplomat_runtime.h` directly) as part of Milestone 5/6 tooling.

## Net effect

Milestone 1: done, with real findings in hand instead of documentation
guesses. `spike-crate/src/lib.rs` and `jolt-runtime/diplomat/*.clj` in this
repo should be treated as **pre-correction** — `out-c-real/` is the
ground truth to design the actual backend against.
