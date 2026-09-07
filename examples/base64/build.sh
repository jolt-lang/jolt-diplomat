#\!/usr/bin/env bash
set -euo pipefail
exec "$(dirname "$0")/../../bind.sh" "$(dirname "$0")/base64_capi" --release
