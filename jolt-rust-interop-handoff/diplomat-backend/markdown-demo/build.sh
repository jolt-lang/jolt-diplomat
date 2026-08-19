#!/usr/bin/env bash
set -euo pipefail

DEMO="$(cd "$(dirname "$0")" && pwd)"
BACKEND="$(cd "$DEMO/.." && pwd)"

CAPI="$DEMO/markdown-capi"
HEADERS="$DEMO/c-headers"
GENERATED="$DEMO/generated"
GENERATOR="$BACKEND/jolt-diplomat-backend/target/debug/jolt-diplomat-backend"

echo "=== 1. Build Rust cdylib ==="
cargo build --release --manifest-path "$CAPI/Cargo.toml"

echo "=== 2. Generate C headers (diplomat-tool) ==="
mkdir -p "$HEADERS"
(cd "$CAPI" && diplomat-tool c "$HEADERS" -e src/lib.rs)

echo "=== 3. Build Jolt generator ==="
cargo build --manifest-path "$BACKEND/jolt-diplomat-backend/Cargo.toml"

echo "=== 4. Generate Clojure bindings + shim C ==="
rm -rf "$GENERATED"
mkdir -p "$GENERATED"
"$GENERATOR" "$CAPI/src/lib.rs" "$GENERATED" "$HEADERS"

echo "=== 5. Compile shim dylib ==="
DYLIB_DIR="$CAPI/target/release"
cc "$GENERATED/generated_shim.c" \
  -I "$HEADERS" \
  -L "$DYLIB_DIR" -lmarkdown_capi \
  -shared -fPIC \
  -o "$DEMO/libmarkdown_shim.dylib" \
  -Wl,-rpath,"$DYLIB_DIR"

echo ""
echo "Done. Artifacts:"
echo "  $CAPI/target/release/libmarkdown_capi.dylib"
echo "  $DEMO/libmarkdown_shim.dylib"
echo "  $GENERATED/diplomat/markdown.clj"
