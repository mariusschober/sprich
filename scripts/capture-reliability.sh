#!/usr/bin/env bash
set -euo pipefail

ADB_BIN="${ADB_BIN:-adb}"
OUTPUT_FILE="${1:-sprich-reliability-log.txt}"

if ! "$ADB_BIN" get-state >/dev/null 2>&1; then
  echo "No ADB device is connected." >&2
  exit 1
fi

"$ADB_BIN" logcat -c
echo "Capturing Sprich, Android runtime, and native crash logs to $OUTPUT_FILE"
echo "Reproduce the failure, then press Ctrl-C. No transcript text is logged by Sprich."

"$ADB_BIN" logcat -b main -b system -b crash -v threadtime \
  'SprichIME:I' 'AudioCapture:I' 'WhisperEngine:I' 'WhisperJNI:I' \
  'AndroidRuntime:E' 'libc:F' 'DEBUG:F' '*:S' | tee "$OUTPUT_FILE"
