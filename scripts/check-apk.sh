#!/usr/bin/env bash
set -euo pipefail
APK=${1:-app/build/outputs/apk/debug/app-debug.apk}
echo "Checking $APK"
ls -lh "$APK"
echo "--- contents (no canary/nemotron expected in assets) ---"
unzip -l "$APK" | grep -E "assets|lib|dex" | head -n 80
echo "--- verify no network in speech (lint) ---"
if grep -r "okhttp" app/src/main/java/com/sprich/app/speech 2>/dev/null; then echo "FAIL: speech imports okhttp"; exit 1; else echo "speech network-free OK"; fi
echo "Done"
