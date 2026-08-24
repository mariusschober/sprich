#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WHISPER="$ROOT/app/src/main/assets/models/whisper-base-q5_1.bin"
EXPECTED_SIZE=50000000 # 50MB release gate
echo "[verify] checking whisper bundled model..."
if [ ! -f "$WHISPER" ]; then
  echo "Missing $WHISPER (placeholder ok for debug, fail for release)"
  exit 1
fi
SIZE=$(stat -f%z "$WHISPER" 2>/dev/null || stat -c%s "$WHISPER")
echo "Found $WHISPER size=$SIZE"
if [ "$SIZE" -lt 1000000 ]; then
  echo "Placeholder detected (<1MB) — debug mock, not releasable. Replace with real 57MB q5_1 before release."
else
  echo "Size ok placeholder or real."
fi
if [ "${RELEASE_CHECK:-0}" = "1" ] && [ "$SIZE" -lt "$EXPECTED_SIZE" ]; then
  echo "RELEASE_CHECK=1 requires >50MB real model"
  exit 1
fi
echo "OK"

# Check optional models not bundled in APK (if APK built)
# Only check for model data (assets/models, files), not native libs libsprich_nemotron.so which is expected
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
  echo "[verify] checking APK does not contain optional model data..."
  if unzip -l "$APK" | grep -E "assets/.*canary|assets/.*nemotron|canary/.*\.onnx|nemotron/.*\.gguf" | grep -q .; then echo "FAIL: canary/nemotron model data leaked into APK"; exit 1; fi
  # Native placeholder libs are expected (libsprich_*.so) — not a leak
  echo "APK clean (model data not bundled, native libs ok)"
fi
