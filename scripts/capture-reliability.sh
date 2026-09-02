#!/usr/bin/env bash
set -euo pipefail
ADB_BIN="${ADB_BIN:-adb}"
if [[ $# -lt 2 ]]; then
  echo "Usage: $0 ADB_SERIAL OUTPUT_FILE" >&2
  exit 2
fi
serial="$1"
output_file="$2"
"$ADB_BIN" -s "$serial" get-state >/dev/null
printf 'Capturing selected app/runtime logs to %s. Reproduce the issue; Ctrl-C stops capture.\n' "$output_file"
printf 'Release logs are intentionally limited. Inspect logs before sharing; other Android components may include private data.\n'
"$ADB_BIN" -s "$serial" logcat -b main -b crash -v threadtime \
  'SprichIME:I' 'SprichAudio:I' 'FastConformer:I' 'WhisperLid:I' 'CanaryEngine:I' \
  'AndroidRuntime:E' 'libc:F' 'DEBUG:F' '*:S' | tee "$output_file"
