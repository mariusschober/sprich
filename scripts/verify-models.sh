#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WHISPER="$ROOT/app/src/main/assets/models/whisper-base-q5_1.bin"
EXPECTED_SIZE=59707625
EXPECTED_SHA256=422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898
echo "[verify] checking whisper bundled model (Canary focus)..."
if [ ! -f "$WHISPER" ]; then
  echo "Whisper not bundled (Canary primary) - OK, skipping whisper check"
else
  SIZE=$(stat -f%z "$WHISPER" 2>/dev/null || stat -c%s "$WHISPER")
  echo "Found $WHISPER size=$SIZE"
  if [ "$SIZE" -ne "$EXPECTED_SIZE" ]; then
    echo "FAIL: expected $EXPECTED_SIZE bytes, got $SIZE"
    exit 1
  fi
  if command -v shasum >/dev/null 2>&1; then
    ACTUAL_SHA256=$(shasum -a 256 "$WHISPER" | awk '{print $1}')
  else
    ACTUAL_SHA256=$(sha256sum "$WHISPER" | awk '{print $1}')
  fi
  if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
    echo "FAIL: model checksum mismatch"
    exit 1
  fi
  echo "OK sha256=$ACTUAL_SHA256"
fi
# Canary model is device-side (files/canary), not bundled. Verify device has it if available.
if [ -f "$ROOT/app/src/main/assets/models/canary.tmpl" ]; then
  echo "Canary bundled template found"
fi

# Check models in APK (Canary focus)
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
  echo "[verify] checking APK for Canary runtime and no bundled model data..."
  if unzip -l "$APK" | grep -E "assets/.*canary|assets/.*nemotron|canary/.*\.onnx|nemotron/.*\.gguf" | grep -q .; then echo "FAIL: canary/nemotron model data should not be bundled (device-side)"; exit 1; fi
  if unzip -l "$APK" | grep -E "libsherpa|libonnxruntime" | grep -q .; then echo "Canary runtime present OK (libsherpa)"; else echo "FAIL: Canary runtime missing (libsherpa not in APK)"; exit 1; fi
  if unzip -l "$APK" | grep -E "whisper-base-q5_1.bin" | grep -q .; then echo "Whisper model still bundled (will be removed)"; else echo "Whisper model not bundled (Canary focus) OK"; fi
fi
