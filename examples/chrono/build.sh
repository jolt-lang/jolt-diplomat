#\!/usr/bin/env bash
set -euo pipefail
exec "$(dirname "$0")/../../bind.sh" "$(dirname "$0")/chrono_capi" --release
