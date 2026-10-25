#!/usr/bin/env python3
"""T4 acoustic subset: play 20 FLEURS clips per language from the Mac speaker into the TCL
microphone through the production IME and a real editor, recording the inserted text.

This measures the production Canary pipeline only. The debug-only Parakeet engine has no
production editor routing in 1.0, so its acoustic numbers are NOT MEASURED (documented).

Prerequisites: release QA build installed and selected as default IME, QA editor installed,
bakeoff corpus in the app's external files dir (see scripts/bakeoff-prepare.py), Mac volume
adjustable. The script restores volume and the prior default IME when done.
"""
import argparse
import json
import pathlib
import re
import subprocess
import time

QA = "com.sprich.qa.editor"
STATE_URI = "content://com.sprich.qa.editor.state/"
IME = "com.sprich.app/.input.ime.SprichIME"
GBOARD = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"


def adb(serial, *args, timeout=30):
    return subprocess.check_output(["adb", "-s", serial, *map(str, args)], text=True, timeout=timeout).strip()


def broadcast(serial, *extra):
    adb(serial, "shell", "am", "broadcast", "-a", f"{QA}.COMMAND", "-p", QA, *extra)


def field_text(serial, index=0):
    raw = adb(serial, "shell", "content", "query", "--uri", STATE_URI)
    _, _, payload = raw.partition("snapshot=")
    data = json.loads(payload)
    for item in data["fields"]:
        if item["field"] == index:
            return item["text"]
    return ""


def focus(serial):
    broadcast(serial, "--es", "op", "reset")
    broadcast(serial, "--es", "op", "focus", "--ei", "field", "0")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--manifest", type=pathlib.Path, required=True)
    parser.add_argument("--wavs", type=pathlib.Path, required=True, help="local directory with the same clip files")
    parser.add_argument("--out", type=pathlib.Path, required=True)
    parser.add_argument("--wait-seconds", type=float, default=20.0)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    clips = [c for c in manifest["clips"] if c.get("acousticSubset")]
    previous_ime = adb(args.serial, "shell", "settings", "get", "secure", "default_input_method")
    volume = adb(args.serial, "shell", "settings", "get", "system", "volume_music_speaker") if False else None
    adb(args.serial, "shell", "ime", "set", IME)
    subprocess.check_call(["osascript", "-e", "set volume output volume 75"])
    results = []
    try:
        adb(args.serial, "shell", "am", "start", "-n", f"{QA}/.EditorActivity")
        time.sleep(2)
        for index, clip in enumerate(clips):
            path = args.wavs / clip["audio"]
            focus(args.serial)
            time.sleep(1.0)
            subprocess.check_call(["afplay", "-v", "1.5", str(path)])
            deadline = time.monotonic() + args.wait_seconds
            text = ""
            while time.monotonic() < deadline:
                time.sleep(0.5)
                current = field_text(args.serial)
                if current and current == text:
                    break
                text = current
            results.append({"id": clip["id"], "language": clip["language"], "reference": clip["reference"],
                            "inserted": text, "durationMs": clip["durationMs"]})
            print(f"[{index+1}/{len(clips)}] {clip['id']} {clip['language']} -> {text!r}", flush=True)
    finally:
        subprocess.check_call(["osascript", "-e", "set volume output volume 25"])
        adb(args.serial, "shell", "ime", "set", previous_ime)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps({"method": "Mac speaker -> TCL microphone -> production IME -> QA EditText",
                                    "engine": "Whisper Tiny LID + Canary 180M Flash (production Automatic)",
                                    "parakeetAcoustic": "NOT MEASURED (debug-only engine has no editor routing)",
                                    "results": results}, indent=2))
    print("wrote", args.out, "clips", len(results))


if __name__ == "__main__":
    main()
