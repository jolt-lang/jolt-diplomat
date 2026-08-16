# Milestone 3 findings — real `jolt v0.7.13` installed and tested, 2026-08-15

**Jolt is now actually installed and running in this environment** —
`curl -sL https://raw.githubusercontent.com/jolt-lang/jolt/main/install |
bash` worked once pinned to `--version v0.7.13` (the install script's
default "resolve latest via `api.github.com`" step hit that API's
unauthenticated rate limit in this sandbox; `git ls-remote --tags` against
`github.com` gave the real latest tag as a workaround with no separate
credential needed). `jolt -e '(+ 1 2)'` → `3`, matching the docs exactly.

This is the first point in the whole project where design decisions could
be checked against the real Chez/Jolt FFI side, not just the C/Rust side.
The findings below supersede parts of `milestone-2-findings.md`.

## Confirmed definitively: no struct-by-value ANYTHING

Not just returns — **parameters too**, verified directly against the real
type dispatcher, not inferred from docs:

```
(ffi/defcfn c-div "div" [:int :int] :struct)
;; => jolt.ffi: unknown foreign type :struct

(ffi/defcfn c-test "..." [[:quot :int] :pointer] :void)
;; => ClassCastException: [:quot :int] cannot be cast to clojure.lang.Named
```

Every literal form tried (`:struct`, a map, a vector-of-pairs) fails the
same way for both parameter and return position. There is also **no
`defstruct`-equivalent macro anywhere in `jolt.ffi`'s public API** — the
full symbol list, read directly from the running binary:

```
foreign-fn loaded? string->ptr c-strerror register-export read-bytes
errno-message dlsym-native export! c-errno-location defcfn read errno
null? c-error-location write-bytes free write-array sizeof load-native
load-library foreign-callable write alloc c-errno-msvc null ptr->string
free-callable read-array
```

Structs are handled purely through `ffi/read`/`ffi/write` at manual byte
offsets — confirms the native-interop guide's documented approach is the
*only* approach, not one option among several.

**This means the Milestone 2 shim design was necessary, not just safer —
it's the only way this works at all.** Good news: the decision made
without proof turned out to be exactly right.

## New, more consequential finding: the shim's OWN signature can't use
   struct-by-value either

Missed in Milestone 2's design. The original `thingy_shim.c` took
`DiplomatStringView s` **by value** as its first parameter — the exact
same shape as the problem it was solving. Confirmed by testing: any
attempt to `defcfn` against a function with a struct-by-value parameter
fails identically to the return case.

**Fix, verified working:** the shim itself must decompose *every*
struct-by-value crossing — parameters and returns alike — into scalars
and pointers, all the way down. `thingy_shim2.c` does this:

```c
void jolt_Thingy_try_create(const char* data, size_t len, void* out);
void jolt_Thingy_describe(const Thingy* self, bool verbose, double scale,
                           DiplomatWrite* write);
```

**Backend design correction:** the generated shim's exported signature
must recursively flatten every struct-typed parameter into its scalar
fields (or an out-pointer for structs too large/complex to flatten), not
just wrap the top-level Result. This is a bigger code-generation surface
than Milestone 2 assumed — worth its own pass over the HIR per function,
not a special case bolted onto Result handling.

## New finding: `diplomat_simple_write` itself returns by value — and
   calling it wrong doesn't crash, it silently returns garbage

`diplomat_runtime.h`'s own helper, the recommended way to construct a
`DiplomatWrite`, has the signature:

```c
DiplomatWrite diplomat_simple_write(char* buf, size_t buf_size);
```

By value, 56 bytes — same problem, one level deeper in the library. This
is worse than the other cases: declaring it from Jolt as returning
`:pointer` (an incorrect guess) did **not** raise a type error or crash.
On x86-64 SysV, structs over 16 bytes return via a caller-supplied hidden
pointer argument that neither side of this mismatched call agreed on —
the call *executes* and returns **whatever garbage happened to be in a
register**, silently. Confirmed: printed `965682416`, a nonsense pointer,
with no error at all.

**This is the most important finding of the whole spike.** A wrong
type-return declaration for a struct-by-value C function is not
guaranteed to fail loudly — it can corrupt silently. The backend must
**never** let a struct-by-value-returning C symbol be `defcfn`'d directly
under any circumstances, including "helper" functions the library
provides as conveniences, and this should be enforced structurally (the
backend refuses to emit a direct `defcfn` for any function whose HIR
return type is a non-opaque struct, full stop — always route through a
generated shim), not left to a human remembering to check.

## New finding: hand-constructing `DiplomatWrite` with null
   `flush`/`grow` function pointers crashes

Original `diplomat.runtime.clj` design left `flush`/`grow` as null,
assuming (wrongly) they'd only be invoked if the buffer needed to grow.
Reproduced the crash directly: `Exception: invalid memory reference` when
calling `Thingy_describe` against a hand-built `DiplomatWrite` with null
function pointers, even though the write fit well within the buffer and
never needed to grow. Diplomat's internal writer evidently calls `flush`
unconditionally to finalize, regardless of whether growth was needed.

**Fix, verified working:** always construct `DiplomatWrite` via the
library's own `diplomat_simple_write` (itself shimmed per the finding
above) rather than hand-assembling the struct's bytes. `diplomat.runtime`
should never construct this struct manually even with a correct offset
table — offsets solve layout, not correct function-pointer values, and
those must come from Diplomat's own constructor.

## Also confirmed along the way (smaller, but real)

- `defcfn`'s numeric type keywords are `:int`, `:uint`, `:long`, `:ulong`,
  `:size_t`, `:uint8`, plus (presumably, by the working/failing pattern)
  a handful more — but **not** `:int32`, `:uint32`, `:usize`, `:short`,
  `:ushort`, all of which were guessed at various points earlier in this
  project and are wrong. `unwrap-result!`/`writeable-capture` in
  `diplomat.runtime.clj` need every type keyword audited against this
  real list, not guessed by analogy to C or Rust type names.
- `ffi/null` is a **value** (`0`), not a zero-arg function — every
  `(ffi/null)` call in the pre-verification code is a bug; `ffi/null`
  used bare is correct.
- `ffi/alloc`/`ffi/write`/`ffi/read`/`ffi/sizeof`/`ffi/read-bytes`/
  `ffi/load-library` all work exactly as documented, first try, no
  surprises — the struct-by-value issue is the whole story, not a sign of
  broader FFI fragility.

## End-to-end proof, real output

Full chain — real Jolt v0.7.13, real `libdiplomat_jolt_spike.a`/`.so`,
real shim, no simulation anywhere:

```
:is-ok 1 :ptr 157783984
:value 42
:sum-with 48
:error-path-is-ok 0
:describe-result Thingy(value=42, scale=2.5)
```

`48 = 42 + 1 + 2 + 3` (slice marshaling correct), error path correctly
reports `is-ok 0`, the writeable string round-trips byte-exact once routed
through the real `diplomat_simple_write` shim.

## Updated backend design requirements (supersedes Milestone 2's version)

1. Backend NEVER emits a direct `defcfn` against any C symbol whose
   Diplomat-generated signature contains a non-opaque struct in ANY
   position, parameter or return — always through a generated shim.
2. The generated shim decomposes every such struct into its scalar
   fields at the C level, recursively.
3. Any `DiplomatWrite` the backend needs is always constructed via a
   shimmed `diplomat_simple_write`, never hand-assembled from an offset
   table, even though the offset table remains necessary for *reading*
   fields back out (`len`, after the call).
4. Audit `diplomat.runtime.clj` and the `jolt-backend/mod.rs` skeleton's
   type-keyword usage against the verified-real list above before relying
   on any of it further.
