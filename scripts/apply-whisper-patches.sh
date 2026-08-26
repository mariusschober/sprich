#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WHISPER="$ROOT/native/whisper"
PATCH="$ROOT/patches/whisper-scheduler-abort.patch"
SOURCE="$WHISPER/src/whisper.cpp"
MARKER='ggml_graph_compute_helper(sched, gf, n_threads, true, abort_callback, abort_callback_data)'

if grep -Fq "$MARKER" "$SOURCE"; then
  echo "Whisper scheduler-abort patch already applied"
  exit 0
fi

git -C "$WHISPER" apply --check "$PATCH"
git -C "$WHISPER" apply "$PATCH"
echo "Applied Whisper scheduler-abort patch"
