# Opt-in Android acceptance tools

This separate, test-only app has native `EditText` fields (multiline, password and PIN), a real WebView and a shell-readable evidence provider. It does not include Sprich code, mock InputConnections or native libraries. Its command receiver/provider require Android's DUMP permission, so ordinary apps cannot read QA text or send editor commands. Use only controlled public test text.

```sh
./gradlew -PincludeQa=true :qa-editor:assembleDebug
adb -s DEVICE install -r -t qa-editor/build/outputs/apk/debug/qa-editor-debug.apk
adb -s DEVICE shell am start -n com.sprich.qa.editor/.EditorActivity
```

Select an installed Sprich release as the current IME. The Python scripts require an explicit serial, installed version and actual screen coordinates; inspect the screenshot before choosing coordinates. Reserve the phone and acoustic space before microphone tests. Each scenario fails if its required capture, fixture or expected editor mutation is absent.

`acoustic-stress.py` plays EN/DE public speech through the Mac speaker and reads real editor callbacks. It asserts one appropriate mutation per cue, no other-field/deletion mutations, and quiet recovery. Use at least 200 cues at ten-second intervals for the 30-minute gate. `lifecycle-stress.py` checks starts and cancellations across field, hide, selection, password and PIN transitions, recording microphone app-op state and actual text callbacks. Lifecycle-only success does not establish transcript quality.

`NativeRuntimeCheck` runs public PCM through JNI loaded from the installed target APK, without replacing Sprich classes. It requires a nondebuggable target by default, installed model files, expected text and expected page size. Example:

```sh
adb -s DEVICE shell am instrument -w -e modes vad,lid,fast,canary -e runs 3 \
  -e expectedPageSize 16384 com.sprich.qa.editor/.NativeRuntimeCheck
```

Read `INSTRUMENTATION_RESULT` and require the JSON status PASS. An instrumentation process exiting successfully does not mean its assertions passed. These tests establish release JNI/model behavior, not microphone/editor integration.

For a deliberately labelled debug baseline, rebuild with `-PqaTargetPackage=com.sprich.app.debug` and pass `-e allowDebug true`. Do not present that as release evidence. Keep raw phone screenshots/logs private when they show personal app state. Commit only controlled public fixtures and aggregate results with source/artifact/device provenance.
