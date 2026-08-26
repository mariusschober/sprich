#!/usr/bin/env bash
set -euo pipefail
APK=${1:-app/build/outputs/apk/debug/app-debug.apk}
echo "Checking $APK"
ls -lh "$APK"
echo "--- contents (no canary/nemotron expected in assets) ---"
unzip -l "$APK" | grep -E "assets|lib|dex" | head -n 80
echo "--- verify no network in speech (lint) ---"
# speech/remote is the explicit opt-in backup STT; all other speech code must stay network-free.
if grep -r "okhttp" app/src/main/java/com/sprich/app/speech --include="*.kt" | grep -v "speech/remote"; then echo "FAIL: unexpected okhttp outside speech/remote"; exit 1; else echo "speech network-free OK (remote opt-in isolated in speech/remote)"; fi
echo "Done"
