# jolt-diplomat

Call Rust libraries from [Jolt](https://jolt-lang.net) using [Diplomat](https://diplomatoptic.com) as the FFI bridge.

One script (`bind.sh`) takes any Diplomat-annotated Rust crate and produces ready-to-use Jolt bindings. A small hand-written runtime library handles the lifetime and marshaling conventions that Diplomat's C ABI requires.

## Architecture

```mermaid
flowchart TD
    subgraph YOUR_CRATE["Your Rust crate"]
        RS["src/lib.rs\n#[diplomat::bridge]"]
    end

    subgraph BIND_SH["bind.sh  (one-time per crate)"]
        direction TB
        S1["① cargo build\n→ libfoo.dylib"]
        S2["② diplomat-tool c\n→ C headers"]
        S3["③ jolt-diplomat-backend\n→ diplomat/*.clj\n→ generated_shim.c"]
        S4["④ cc\n→ libfoo_shim.dylib"]
        S1 --> S2 --> S3 --> S4
    end

    subgraph SHIM["generated_shim.c  〔why it exists〕"]
        SH1["Result&lt;T,E&gt; returns\nstruct-by-value → out-pointer"]
        SH2["DiplomatWrite\nwrap diplomat_simple_write"]
        SH3["Struct returns\nmemcpy into caller buffer\n+ sizeof helper"]
        SH4["Option&lt;Prim&gt; returns\ndecompose to (T*, bool*)"]
    end

    subgraph GENERATED["generated/diplomat/*.clj  〔auto-generated〕"]
        G1["defopaque + destroy binding"]
        G2["defcfn per method\n(direct or via shim)"]
        G3["field offset reads\nfor struct returns"]
        G4["unwrap-result! calls\nfor fallible methods"]
    end

    subgraph RUNTIME["runtime/  〔hand-written, ship once〕"]
        R1["defopaque macro\nopaque lifetime protocol"]
        R2["with-opaque / when-opaque\nscoped resource management"]
        R3["unwrap-result!\nResult → ex-info"]
        R4["DiplomatWrite helpers\nsimple-write! / writeable-capture"]
        R5["load! macro\nloads cdylib + shim dylib"]
        R6["read-u16\n(Jolt ffi has no 16-bit read)"]
        R7["with-primitive-buffer\n&[T] slice marshaling"]
    end

    subgraph JOLT["Your Jolt program"]
        J["(dr/with-opaque [u (url/parse s)]\n  (url/host u))"]
    end

    RS --> BIND_SH
    S3 --> SHIM
    S3 --> GENERATED
    GENERATED --> JOLT
    RUNTIME --> JOLT
    SHIM --> JOLT
```

### Why the shim exists

Diplomat's C backend emits several ABI shapes that Jolt's `ffi/defcfn` cannot express directly:

| Shape | Problem | Shim solution |
|---|---|---|
| `Result<T,E>` | Returned as a named struct by value; no struct-by-value return in Jolt ffi | Shim takes an out-pointer, writes the struct through it |
| `DiplomatWrite` | `diplomat_simple_write` returns `DiplomatWrite` by value (56 bytes) | Shim wraps it with an out-pointer |
| Struct returns | Any `-> MyStruct` crosses as struct-by-value | Shim `memcpy`s into caller buffer; sizeof helper lets Jolt allocate the right size |
| `Option<Prim>` | C ABI is a per-function `{T ok; bool is_ok}` result struct | Shim decomposes to `(T* out_val, bool* out_is_ok)` |

### What the runtime handles

| Concern | Why it can't be generated |
|---|---|
| Opaque lifetime (`with-opaque`, `when-opaque`) | Scoping convention, not derivable from a single type's API |
| `unwrap-result!` | Common across all fallible methods; one copy is better than N |
| `read-u16` | Jolt ffi has no 16-bit `foreign-ref` type; two `uint8` reads + `bit-or` |
| `with-primitive-buffer` | `&[T]` marshaling is identical for every slice param regardless of type |
| `load!` | Loads both the cdylib and shim dylib in one call |

## Layout

```
jolt-diplomat/
├── runtime/          — Jolt library; add as :local/root or :git/url dep
├── backend/          — Rust generator (jolt-diplomat-backend)
├── bind.sh           — full pipeline: cargo → diplomat-tool → generator → cc
└── examples/
    ├── url/          — url crate: nullable prim, struct return, fallible
    ├── regex/        — regex crate: nullable write, opaque error
    ├── semver/       — semver crate: cross-opaque method params
    ├── base64/       — base64 + hex: &[u8] slice params
    ├── json/         — serde_json: nullable opaque, enum return
    └── chrono/       — chrono: struct return with mixed field types
```

## Usage

### 1. Annotate your crate

```rust
#[diplomat::bridge]
mod ffi {
    #[diplomat::opaque]
    pub struct MyType(inner::MyType);

    impl MyType {
        pub fn parse(s: &str) -> Result<Box<MyType>, Box<MyError>> { ... }
        pub fn value(&self) -> u32 { ... }
    }
}
```

### 2. Run bind.sh

```bash
bash bind.sh path/to/my_capi --release
```

Outputs: `generated/diplomat/*.clj`, `generated/generated_shim.c`, `libmy_capi_shim.dylib`.

### 3. Add runtime dep

```edn
; deps.edn
{:paths ["src" "../generated"]
 :deps {jolt-diplomat-runtime/jolt-diplomat-runtime
        {:local/root "../../../runtime"}}}
```

### 4. Call from Jolt

```clojure
(require '[diplomat.runtime :as dr])
(dr/load! demo-dir "my_capi")
(require '[diplomat.my-type :as mt])

(dr/with-opaque [x (mt/parse "hello")]
  (println (mt/value x)))
```

## Running the examples

### Prerequisites

**Rust toolchain**
```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
```

**diplomat-tool** (must be 0.14–0.15; 0.16+ changes the HIR and is not yet supported)
```bash
cargo install diplomat-tool --version "^0.15"
```

**Jolt** v0.7+  
Follow the [Jolt install guide](https://jolt-lang.net/docs/install.html). Verify with:
```bash
jolt --version   # should print v0.7.x or later
```

**C compiler** — on macOS install Xcode Command Line Tools if not already present:
```bash
xcode-select --install
```

### Build an example

Each example ships with pre-generated bindings and a pre-compiled shim dylib, so for a quick run you only need Jolt:

```bash
cd examples/chrono/jolt-project
jolt run -m demo
```

To rebuild from source (e.g. after modifying the Rust crate):

```bash
cd examples/chrono
bash build.sh          # runs the full pipeline: cargo → diplomat-tool → generator → cc
cd jolt-project
jolt run -m demo
```

### All examples at once

```bash
for demo in url regex semver base64 json chrono; do
  echo "=== $demo ==="
  (cd examples/$demo/jolt-project && jolt run -m demo)
done
```

### What each example demonstrates

| Example | Rust crate | Key ABI shapes |
|---|---|---|
| `url` | [`url`](https://crates.io/crates/url) | fallible ctor, `Option<u16>` return, struct return |
| `regex` | [`regex`](https://crates.io/crates/regex) | nullable write, opaque error type |
| `semver` | [`semver`](https://crates.io/crates/semver) | cross-opaque method params |
| `base64` | [`base64` + `hex`](https://crates.io/crates/base64) | `&[u8]` slice params |
| `json` | [`serde_json`](https://crates.io/crates/serde_json) | nullable opaque return, enum return |
| `chrono` | [`chrono`](https://crates.io/crates/chrono) | struct return with mixed field types, nullable opaque |

## Requirements

- Rust + Cargo
- `diplomat-tool` 0.14–0.15 (`cargo install diplomat-tool --version "^0.15"`)
- [Jolt](https://jolt-lang.net) v0.7+
- `cc` (Xcode CLT on macOS)
