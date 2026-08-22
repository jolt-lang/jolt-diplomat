#!/usr/bin/env bash
set -euo pipefail
DEMO="$(cd "$(dirname "$0")" && pwd)"
BACKEND="$(cd "$DEMO/../" && pwd)"
bash "$BACKEND/jolt-bind.sh" "$DEMO/chrono-capi" --release
