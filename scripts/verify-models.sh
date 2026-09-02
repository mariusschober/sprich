#!/usr/bin/env bash
# This is an input-integrity gate, not model-download or device-runtime proof.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 "$ROOT/scripts/verify-inputs.py" "$@"
