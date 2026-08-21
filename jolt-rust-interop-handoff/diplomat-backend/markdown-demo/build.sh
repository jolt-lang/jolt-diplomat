#!/usr/bin/env bash
set -euo pipefail
exec "$(dirname "$0")/../jolt-bind.sh" "$(dirname "$0")/markdown-capi" --release
