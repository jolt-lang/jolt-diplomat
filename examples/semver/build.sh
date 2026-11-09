#!/usr/bin/env bash
set -euo pipefail
exec "$(dirname "$0")/../../bind.clj" "$(dirname "$0")/semver_capi" --release
