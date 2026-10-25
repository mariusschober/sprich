#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Prefer an explicitly supplied APK, then a signed release build, then the unsigned artifact.
if [ "$#" -ge 1 ]; then
  APK="$1"
elif [ -f "$ROOT/app/build/outputs/apk/release/app-release.apk" ]; then
  APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
else
  APK="$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
fi
exec python3 "$ROOT/scripts/verify-release.py" "$APK"
