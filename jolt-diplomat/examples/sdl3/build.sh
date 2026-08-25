#!/usr/bin/env bash
set -euo pipefail
exec "$(dirname "$0")/../../bind.sh" "$(dirname "$0")/sdl3_capi" --release
