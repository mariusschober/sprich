# Recording the human-voice corpus (maintainer task)

The 1.0 accuracy claim needs real human speech recorded on the actual device. Synthetic or
loudspeaker playback is **not** human-voice accuracy evidence. This workflow records each prompt
through the production IME path, keeps the exact frozen PCM on the device, and scores WER/CER
offline. Audio never leaves your machine and is never committed.

## What you need

- The TCL T807D (or another ARM64 test device) attached, USB debugging on, screen unlocked.
- A quiet room. Do not run Tasker/assistant audio, calls, or another speaker test.
- Mac with `adb`, `python3`, JDK 17, Android SDK 36, NDK 27.0.12077973.

## One-time setup

```sh
cd /path/to/Sprich
# 1. Build the debug app + the QA editor and test APK (all from this commit).
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew -PincludeQa=true :qa-editor:assembleDebug
SERIAL=<your-device-serial>
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$SERIAL" install -r -t qa-editor/build/outputs/apk/debug/qa-editor-debug.apk
# 2. Enable the debug IME and make it the current keyboard.
adb -s "$SERIAL" shell ime enable com.sprich.app.debug/com.sprich.app.input.ime.SprichIME
adb -s "$SERIAL" shell ime set com.sprich.app.debug/com.sprich.app.input.ime.SprichIME
adb -s "$SERIAL" shell pm grant com.sprich.app.debug android.permission.RECORD_AUDIO
# 3. Provision the Automatic model in the debug package (real production download, ~475 MB).
adb -s "$SERIAL" shell am instrument -w -e class com.sprich.app.FreshInstallDeviceTest \
  com.sprich.app.debug.test/androidx.test.runner.AndroidJUnitRunner
# 4. Turn on frozen-PCM capture (debug builds only; no production UI).
adb -s "$SERIAL" shell am instrument -w -e class \
  com.sprich.app.CorpusCaptureEnableTest#enableDebugWavCapture \
  com.sprich.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Fill in the `prompt` column of [corpus-manifest-template.csv](corpus-manifest-template.csv) with
the 130 sentences you will read (counts and conditions: [CORPUS.md](CORPUS.md)); you may type the
`reference` after speaking, the recorder asks for it. Keep the order: WAVs are paired by dictation
order.

## Record

```sh
mkdir -p local_benchmark          # gitignored: never commit audio or manifests with speech
python3 scripts/corpus-record.py --serial "$SERIAL" \
  --manifest docs/corpus-manifest-template.csv --out local_benchmark/manifest.csv
```

For each prompt the script clears the QA editor, focuses the field (the IME starts listening),
waits for the inserted text, and stores it as the hypothesis. Press `s` to skip, `q` to stop and
resume later; the output CSV is saved after every row. Speak naturally; do not read model output.
Whisper rows must be **actually whispered**, not quiet speech.

Then pull the audio (debug package only, app-private no-backup directory):

```sh
adb -s "$SERIAL" exec-out run-as com.sprich.app.debug tar -c -C noBackupFilesDir sprich_replay \
  > /tmp/sprich-corpus.tar
mkdir -p /tmp/sprich-corpus && tar -xf /tmp/sprich-corpus.tar -C /tmp/sprich-corpus
```

## Score

```sh
python3 scripts/corpus-score.py \
  --manifest local_benchmark/manifest.csv \
  --wavs /tmp/sprich-corpus/sprich_replay \
  --out local_benchmark/report.json
```

Report WER/CER separately for en/normal, en/short, en/whisper, de/normal, de/short, de/whisper,
es/normal, fr/normal; never merge languages. Blank rate is reported per group. Keep the report and
the model/version/device used with it; the audio stays local.

## Cleanup and privacy

```sh
# Turn capture off, restore your normal keyboard, remove the debug data when done.
adb -s "$SERIAL" shell am instrument -w -e class \
  com.sprich.app.CorpusCaptureEnableTest#disableDebugWavCapture \
  com.sprich.app.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$SERIAL" shell ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
adb -s "$SERIAL" shell pm clear com.sprich.app.debug
rm -rf local_benchmark /tmp/sprich-corpus /tmp/sprich-corpus.tar
```

The release build cannot export audio by design; this is why the recorder uses the debug package.
Never publish, commit or share the WAVs, manifests or screenshots that show personal text.
