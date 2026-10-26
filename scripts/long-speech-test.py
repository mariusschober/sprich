#!/usr/bin/env python3
"""R1 gate: continuous public speech (20/40/60 s, no added pauses) played into the TCL
microphone through the release QA build and a real editor. Reports inserted text length, the
final-phrase check and blank status. Loudspeaker playback, not human speech."""
import argparse, json, pathlib, subprocess, time

QA = "com.sprich.qa.editor"
STATE_URI = "content://com.sprich.qa.editor.state/"
IME = "com.sprich.app/.input.ime.SprichIME"


def adb(serial, *args, timeout=60):
    return subprocess.check_output(["adb", "-s", serial, *map(str, args)], text=True, timeout=timeout).strip()


def field(serial):
    raw = adb(serial, "shell", "content", "query", "--uri", STATE_URI)
    _, _, p = raw.partition("snapshot=")
    return json.loads(p)["fields"][0]["text"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", type=pathlib.Path, required=True)
    ap.add_argument("--gain", type=float, default=3.0)
    a = ap.parse_args()
    prev = adb(a.serial, "shell", "settings", "get", "secure", "default_input_method")
    adb(a.serial, "shell", "ime", "set", IME)
    subprocess.check_call(["osascript", "-e", "set volume output volume 100"])
    results = []
    try:
        adb(a.serial, "shell", "am", "start", "-n", f"{QA}/.EditorActivity")
        time.sleep(2)
        # warm-up
        adb(a.serial, "shell", "am", "broadcast", "-a", f"{QA}.COMMAND", "-p", QA, "--es", "op", "reset")
        adb(a.serial, "shell", "am", "broadcast", "-a", f"{QA}.COMMAND", "-p", QA, "--es", "op", "focus", "--ei", "field", "0")
        time.sleep(2.5)
        subprocess.check_call(["afplay", "-v", str(a.gain), "/tmp/long-20.wav"])
        time.sleep(8)
        for seconds in (20, 40, 60):
            adb(a.serial, "shell", "am", "broadcast", "-a", f"{QA}.COMMAND", "-p", QA, "--es", "op", "reset")
            for _ in range(20):
                if not field(a.serial):
                    break
                time.sleep(0.2)
            adb(a.serial, "shell", "am", "broadcast", "-a", f"{QA}.COMMAND", "-p", QA, "--es", "op", "focus", "--ei", "field", "0")
            time.sleep(2.2)
            start = time.monotonic()
            subprocess.check_call(["afplay", "-v", str(a.gain), f"/tmp/long-{seconds}.wav"])
            end = time.monotonic()
            # wait until the field stops growing for 12 s after the expected audio duration
            deadline = end + 35 + seconds * 0.5
            last = ""
            stable_since = None
            while time.monotonic() < deadline:
                time.sleep(0.5)
                text = field(a.serial)
                if text != last:
                    last = text
                    stable_since = time.monotonic()
                elif stable_since and time.monotonic() - stable_since >= 12:
                    break
            text = field(a.serial)
            lowered = text.lower()
            results.append({
                "seconds": seconds,
                "insertedChars": len(text),
                "blank": not text.strip(),
                "hasFinalPhrase": "your country" in lowered,
                "tail": text[-120:],
            })
            print(results[-1], flush=True)
    finally:
        subprocess.check_call(["osascript", "-e", "set volume output volume 25"])
        adb(a.serial, "shell", "ime", "set", prev)
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps({"method": "Mac speaker -> TCL mic -> release QA IME -> real EditText; 22/44/66 s continuous public speech",
                                 "results": results}, indent=2))
    print("wrote", a.out)


if __name__ == "__main__":
    main()
