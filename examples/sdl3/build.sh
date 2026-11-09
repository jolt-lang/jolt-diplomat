#!/usr/bin/env bash
set -euo pipefail
exec "$(dirname "$0")/../../bind.clj" "$(dirname "$0")/sdl3_capi" --release
