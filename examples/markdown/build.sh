#!/usr/bin/env bash
set -euo pipefail
exec "$(dirname "$0")/../../bind.clj" "$(dirname "$0")/markdown_capi" --release
