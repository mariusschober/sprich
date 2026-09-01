#!/usr/bin/env bash
set -euo pipefail
APK=${1:-app/build/outputs/apk/debug/app-debug.apk}
echo "Checking $APK"
ls -lh "$APK"
echo "--- contents (no canary/nemotron expected in assets) ---"
unzip -l "$APK" | grep -E "assets|lib|dex" | head -n 80
echo "--- verify no network in speech (lint) ---"
# speech/remote is the explicit opt-in backup STT; shared client reuse in TranscriptionCoordinator and IME is allowed (P0-18 pooled connections)
# Local engines (canary/fastconformer/lid/nemotron) must remain network-free.
if grep -r "okhttp" app/src/main/java/com/sprich/app/speech --include="*.kt" | grep -v "speech/remote" | grep -v "TranscriptionCoordinator"; then echo "FAIL: unexpected okhttp outside speech/remote/TranscriptionCoordinator"; exit 1; else echo "speech network-free OK (remote opt-in isolated, coordinator pooled reuse allowed)"; fi
# Also ensure local ASR engines never import okhttp
if grep -r "okhttp" app/src/main/java/com/sprich/app/speech/canary app/src/main/java/com/sprich/app/speech/fastconformer app/src/main/java/com/sprich/app/speech/lid app/src/main/java/com/sprich/app/speech/nemotron --include="*.kt" 2>/dev/null; then echo "FAIL: local ASR engine must not use okhttp"; exit 1; else echo "local ASR network-free OK"; fi
echo "Done"
