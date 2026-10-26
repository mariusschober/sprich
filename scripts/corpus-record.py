#!/usr/bin/env python3
"""Interactive recorder for the Sprich human-voice corpus (maintainer-run).

The maintainer reads prompts aloud into the device microphone; Sprich transcribes them as usual;
this script pairs each prompt with the inserted text, writes the manifest, and leaves the exact
frozen PCM WAVs in the debug build's no-backup directory for scoring.

It never invokes a recognizer itself and never uploads anything. All audio stays on the device
until you pull it deliberately.

See docs/CORPUS_RECORDING.md for the full setup and privacy rules.

Usage:
  python3 scripts/corpus-record.py --serial SERIAL --manifest docs/corpus-manifest-template.csv \\
      --out local_benchmark/manifest.csv
"""
import argparse
import csv
import json
import pathlib
import re
import subprocess
import sys
import time

QA_PACKAGE = "com.sprich.qa.editor"
QA_STATE_URI = "content://com.sprich.qa.editor.state/"


def adb(serial: str, *args: str, timeout: int = 30) -> str:
    return subprocess.check_output(["adb", "-s", serial, *map(str, args)], text=True, timeout=timeout).strip()


def broadcast(serial: str, *extra: str) -> None:
    adb(serial, "shell", "am", "broadcast", "-a", f"{QA_PACKAGE}.COMMAND", "-p", QA_PACKAGE, *extra)


def field_state(serial: str, field: int) -> dict:
    raw = adb(serial, "shell", "content", "query", "--uri", QA_STATE_URI)
    _, _, payload = raw.partition("snapshot=")
    data = json.loads(payload)
    for item in data["fields"]:
        if item["field"] == field:
            return item
    raise SystemExit(f"field {field} missing from QA state: {payload[:200]}")


def wait_for_text(serial: str, field: int, timeout: float) -> str:
    """Wait for a non-empty, stable field text. Returns '' when nothing was inserted."""
    deadline = time.monotonic() + timeout
    last = ""
    stable_since = None
    while time.monotonic() < deadline:
        text = field_state(serial, field)["text"]
        if text:
            if text != last:
                last = text
                stable_since = time.monotonic()
            elif stable_since is not None and time.monotonic() - stable_since >= 1.5:
                return text
        time.sleep(0.25)
    return last


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb", help="adb binary (default: adb)")
    parser.add_argument("--manifest", type=pathlib.Path, required=True,
                        help="template CSV with id,language,type,prompt,reference,hypothesis")
    parser.add_argument("--out", type=pathlib.Path, required=True,
                        help="completed CSV (resumable; existing hypotheses are kept)")
    parser.add_argument("--field", type=int, default=0, help="QA editor field index (default 0)")
    parser.add_argument("--wait-seconds", type=float, default=25.0,
                        help="max seconds to wait for an insertion after you stop speaking")
    parser.add_argument("--language", help="only record rows for this language (en/de/es/fr)")
    args = parser.parse_args()

    rows = list(csv.DictReader(args.manifest.open(encoding="utf-8")))
    if not rows or not {"id", "prompt", "reference", "hypothesis"} <= set(rows[0]):
        print("FAIL: template needs id,language,type,prompt,reference,hypothesis", file=sys.stderr)
        return 2
    done = {}
    if args.out.is_file():
        for row in csv.DictReader(args.out.open(encoding="utf-8")):
            done[row["id"]] = row

    def save(current: list[dict]) -> None:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        with args.out.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
            writer.writeheader()
            writer.writerows(current)

    completed: list[dict] = []
    for row in rows:
        if args.language and row["language"] != args.language:
            completed.append(row)
            continue
        previous = done.get(row["id"])
        if previous and (previous.get("hypothesis") or previous.get("blank", "").lower() == "true"):
            completed.append(previous)
            continue
        prompt = (row.get("prompt") or "").strip()
        if not prompt:
            print(f"skip {row['id']}: fill the prompt column first")
            completed.append(row)
            continue
        print()
        print(f"[{row['id']}] {row['language']} / {row['type']}")
        print(f"  Say: {prompt}")
        action = input("  Press Enter when ready, 's' to skip, 'q' to save and quit: ").strip().lower()
        if action == "q":
            save(completed + rows[len(completed):])
            return 0
        if action == "s":
            completed.append(row)
            continue
        broadcast(args.serial, "--es", "op", "reset")
        broadcast(args.serial, "--es", "op", "focus", "--ei", "field", str(args.field))
        print("  Listening… speak now.")
        text = wait_for_text(args.serial, args.field, args.wait_seconds)
        print(f"  Inserted: {text!r}")
        if text:
            answer = input("  Enter=accept, 'e'=type correction, 'b'=count as blank: ").strip().lower()
            if answer == "b":
                text = ""
            elif answer == "e":
                text = input("  Actual inserted text: ").strip()
        reference = (row.get("reference") or "").strip()
        if not reference:
            reference = input("  Type the exact words you said (reference): ").strip()
        row["reference"] = reference
        row["hypothesis"] = text
        completed.append(row)
        save(completed + rows[len(completed):])
        print(f"  saved {args.out} ({len(completed)}/{len(rows)})")
    save(completed)
    print(f"done: {args.out}")
    print("Pull WAVs with: adb -s %s exec-out run-as com.sprich.app.debug "
          "tar -c -C noBackupFilesDir sprich_replay > /tmp/sprich-corpus.tar" % args.serial)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
