#!/usr/bin/env python3
"""End-to-end dictation latency: end of speech -> inserted text visible in a real editor.

Drives the production IME through the QA editor, plays a short public speech clip from the Mac
speaker, and polls the editor state. The measured interval is afplay-return (end of played speech)
to the first non-empty editor text. Polling costs up to one interval (default 25 ms), which is
reported as method overhead. Not a human-speech or hands-on latency claim.
"""
import argparse
import json
import pathlib
import statistics
import subprocess
import time

QA = "com.sprich.qa.editor"
STATE_URI = "content://com.sprich.qa.editor.state/"
IME = "com.sprich.app/.input.ime.SprichIME"


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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--wav", type=pathlib.Path, required=True)
    parser.add_argument("--runs", type=int, default=30)
    parser.add_argument("--poll-ms", type=int, default=25)
    parser.add_argument("--out", type=pathlib.Path, required=True)
    parser.add_argument("--volume", type=int, default=100)
    parser.add_argument("--gain", type=float, default=3.0)
    args = parser.parse_args()
    previous_ime = adb(args.serial, "shell", "settings", "get", "secure", "default_input_method")
    adb(args.serial, "shell", "ime", "set", IME)
    subprocess.check_call(["osascript", "-e", f"set volume output volume {args.volume}"])
    samples = []
    try:
        adb(args.serial, "shell", "am", "start", "-n", f"{QA}/.EditorActivity")
        time.sleep(2)
        # Warm-up capture, discarded (engine load and first IME session).
        broadcast(args.serial, "--es", "op", "reset")
        broadcast(args.serial, "--es", "op", "focus", "--ei", "field", "0")
        time.sleep(2.5)
        subprocess.check_call(["afplay", "-v", str(args.gain), str(args.wav)])
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            time.sleep(0.05)
            if field_text(args.serial):
                break
        for index in range(args.runs):
            broadcast(args.serial, "--es", "op", "reset")
            empty_deadline = time.monotonic() + 3
            while time.monotonic() < empty_deadline and field_text(args.serial):
                time.sleep(0.03)
            broadcast(args.serial, "--es", "op", "focus", "--ei", "field", "0")
            time.sleep(1.2)
            subprocess.check_call(["afplay", "-v", str(args.gain), str(args.wav)])
            end = time.monotonic()
            inserted_at = None
            deadline = end + 30
            while time.monotonic() < deadline:
                time.sleep(args.poll_ms / 1000.0)
                if field_text(args.serial):
                    inserted_at = time.monotonic()
                    break
            if inserted_at is None:
                samples.append(None)
                print(f"[{index+1}/{args.runs}] NO INSERTION", flush=True)
            else:
                latency_ms = (inserted_at - end) * 1000.0
                samples.append(latency_ms)
                print(f"[{index+1}/{args.runs}] {latency_ms:.0f} ms", flush=True)
            time.sleep(0.3)
    finally:
        subprocess.check_call(["osascript", "-e", "set volume output volume 25"])
        adb(args.serial, "shell", "ime", "set", previous_ime)
    valid = [s for s in samples if s is not None]
    valid_sorted = sorted(valid)
    def pct(p):
        if not valid_sorted:
            return None
        return valid_sorted[min(len(valid_sorted) - 1, int(round(p * (len(valid_sorted) - 1))))]
    report = {
        "method": "Mac speaker -> TCL microphone -> production IME -> QA EditText; latency = afplay return to first non-empty editor text",
        "pollMs": args.poll_ms,
        "runs": args.runs,
        "insertions": len(valid),
        "latencyMs": valid,
        "p50Ms": statistics.median(valid) if valid else None,
        "p95Ms": pct(0.95),
        "minMs": min(valid) if valid else None,
        "maxMs": max(valid) if valid else None,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2))
    print(f"p50={report['p50Ms']} p95={report['p95Ms']} insertions={len(valid)}/{args.runs} -> {args.out}")


if __name__ == "__main__":
    main()
