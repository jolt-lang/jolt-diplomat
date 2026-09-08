# tantivy example

Full-text product search over a 20-item catalog, binding the [tantivy](https://crates.io/crates/tantivy) Rust search engine to Jolt via Diplomat.

## Business case

An e-commerce catalog needs BM25-ranked full-text search with sub-millisecond query latency, field-level boosting (title scores 2× over description), and live category/price metadata on every result. tantivy delivers this; the Diplomat bridge makes it callable from any Jolt program without shipping a separate search service.

## Schema

| Field | Type | Options | Purpose |
|---|---|---|---|
| `title` | TEXT | STORED | Primary search surface, 2× boosted |
| `description` | TEXT | STORED | Secondary search surface; source for 120-char snippets |
| `category` | STRING | STORED, FAST | Facet-ready (not tokenized) |
| `price_cents` | U64 | STORED, FAST, INDEXED | Exact/range filtering |

## API → business mapping

| Method | ABI shape | Purpose |
|---|---|---|
| `SearchIndex::new_in_ram()` | infallible opaque ctor | dev/test — no disk I/O |
| `SearchIndex::new_on_disk(path)` | fallible opaque ctor | production persistent index |
| `SearchIndex::add_product(...)` | fallible `&self` method | buffer a document for the next commit |
| `SearchIndex::commit()` | **blocking** fallible method | flush writer + reload reader; marks `:blocking` in generated binding |
| `SearchIndex::doc_count()` | plain scalar | verify indexing |
| `SearchIndex::search(query, limit)` | infallible, returns `Box<ResultSet>` | BM25 query; empty ResultSet on parse error |
| `ResultSet::count()` | plain scalar | iterate results |
| `ResultSet::get_title/description/category/snippet(i, write)` | writeable param | string accessors |
| `ResultSet::get_score/price_cents(i)` | plain scalar | numeric accessors |

## Notable ABI patterns

- **Blocking commit**: `commit()` carries `/// jolt-diplomat: blocking` so the generated `defcfn` gets `:blocking`, keeping the JVM GC cooperative during a potentially long disk flush.
- **Opaque chain**: `SearchIndex::search` returns `Box<ResultSet>` (a different opaque). The generator handles this as an infallible call returning a pointer — the `Result` version was skipped because the generator only supports `Result<Box<Self>, E>` for fallible opaque returns.
- **Accessor pattern**: `ResultSet` exposes per-index getters rather than returning a `Vec` — matches Diplomat's opaque model where owned collections can't cross the FFI directly.

## Running

```bash
# Quick run (pre-built bindings + shim included)
cd examples/tantivy/jolt-project
jolt run -m demo

# Smoke test (non-interactive)
jolt run -m demo src/demo.clj --test

# Rebuild from source
cd examples/tantivy
../../bind.clj tantivy_capi
cd jolt-project && jolt run -m demo
```

## Rust tests

```bash
cd examples/tantivy/tantivy_capi
cargo test
```

Six unit tests cover: doc count after commit, top-result relevance, empty query, limit enforcement, price round-trip, out-of-bounds accessor safety.
