#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 "$ROOT/scripts/verify-release.py" "${1:-$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk}"
