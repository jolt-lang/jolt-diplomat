#!/usr/bin/env bash
# jolt-bind.sh — build a diplomat-annotated Rust crate into Jolt FFI bindings.
#
# Usage:
#   jolt-bind.sh <capi-dir> [--release]
#
# Where <capi-dir> is the root of a Rust crate with:
#   - crate-type = ["cdylib"]
#   - #[diplomat::bridge] in src/lib.rs
#
# Outputs alongside <capi-dir>:
#   c-headers/          — diplomat-generated C headers
#   generated/          — generated_shim.c + diplomat/*.clj
#   lib<name>_shim.dylib — compiled C shim
#
# The lib name is derived from [lib] name in Cargo.toml.

set -euo pipefail

if [[ $# -lt 1 ]]; then
    echo "Usage: jolt-bind.sh <capi-dir> [--release]" >&2
    exit 1
fi

CAPI="$(cd "$1" && pwd)"
PROFILE="${2:-}"
CARGO_PROFILE_FLAG="--release"
BUILD_DIR="release"
if [[ "$PROFILE" != "--release" ]]; then
    CARGO_PROFILE_FLAG=""
    BUILD_DIR="debug"
fi

DEMO="$(dirname "$CAPI")"
BACKEND="$(cd "$(dirname "$0")" && pwd)"
HEADERS="$DEMO/c-headers"
GENERATED="$DEMO/generated"
GENERATOR="$BACKEND/jolt-diplomat-backend/target/debug/jolt-diplomat-backend"

# Derive lib name from Cargo.toml [lib] name field.
LIB_NAME="$(grep -A2 '^\[lib\]' "$CAPI/Cargo.toml" | grep 'name' | head -1 | sed 's/.*= *"\(.*\)"/\1/')"
if [[ -z "$LIB_NAME" ]]; then
    echo "Error: could not find [lib] name in $CAPI/Cargo.toml" >&2
    exit 1
fi

DYLIB_DIR="$CAPI/target/$BUILD_DIR"
SHIM_OUT="$DEMO/lib${LIB_NAME}_shim.dylib"

echo "=== jolt-bind: $LIB_NAME ==="
echo "    capi:      $CAPI"
echo "    headers:   $HEADERS"
echo "    generated: $GENERATED"
echo "    shim:      $SHIM_OUT"
echo ""

echo "--- 1. Build Rust cdylib ---"
cargo build $CARGO_PROFILE_FLAG --manifest-path "$CAPI/Cargo.toml"

echo "--- 2. Generate C headers (diplomat-tool) ---"
mkdir -p "$HEADERS"
(cd "$CAPI" && diplomat-tool c "$HEADERS" -e src/lib.rs)

echo "--- 3. Build Jolt generator ---"
cargo build --manifest-path "$BACKEND/jolt-diplomat-backend/Cargo.toml"

echo "--- 4. Generate Clojure bindings + shim C ---"
rm -rf "$GENERATED"
mkdir -p "$GENERATED"
"$GENERATOR" "$CAPI/src/lib.rs" "$GENERATED" "$HEADERS"

echo "--- 5. Compile shim dylib ---"
cc "$GENERATED/generated_shim.c" \
    -I "$HEADERS" \
    -L "$DYLIB_DIR" -l"$LIB_NAME" \
    -shared -fPIC \
    -o "$SHIM_OUT" \
    -Wl,-rpath,"$DYLIB_DIR"

echo ""
echo "Done."
echo "  cdylib: $DYLIB_DIR/lib${LIB_NAME}.dylib"
echo "  shim:   $SHIM_OUT"
echo "  clj:    $GENERATED/diplomat/"
