#!/usr/bin/env python3
"""Score a recorded human corpus: WER/CER, blank rate, per language and condition.

Host-only tooling. It reads a manifest you fill in while/after recording and the WAVs pulled
from a debug Sprich build. It performs no recognition and never uploads anything.

Manifest CSV columns (header required):
  id,language,type,reference,hypothesis
  en-001,en,normal,I love you.,I love you
  de-014,de,whisper,Ich komme später.,ich komme spater
  en-041,en,short,Tomorrow.,                    <- blank: the app inserted nothing

`reference` is what you actually said (manually verified, never model output).
`hypothesis` is the text Sprich inserted (blank means no insertion / cancelled).
Rows with an empty `hypothesis` are counted as blanks and excluded from WER/CER means.

Usage:
  python3 scripts/corpus-score.py --manifest local_benchmark/manifest.csv \
      --wavs /tmp/sprich-corpus/wavs --out local_benchmark/report.json
"""
import argparse
import csv
import json
import pathlib
import re
import sys
import unicodedata
from collections import defaultdict

PUNCT = re.compile(r"[^\w\s]", re.UNICODE)


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFKC", text).lower()
    text = PUNCT.sub(" ", text)
    return " ".join(text.split())


def words(text: str) -> list[str]:
    normalized = normalize(text)
    return normalized.split() if normalized else []


def chars(text: str):
    return list(normalize(text).replace(" ", ""))


def levenshtein(a, b) -> int:
    if len(a) < len(b):
        a, b = b, a
    previous = list(range(len(b) + 1))
    for i, item_a in enumerate(a, 1):
        current = [i]
        for j, item_b in enumerate(b, 1):
            insert = previous[j] + 1
            delete = current[j - 1] + 1
            replace = previous[j - 1] + (0 if item_a == item_b else 1)
            current.append(min(insert, delete, replace))
        previous = current
    return previous[len(b)]


def wer(reference: str, hypothesis: str) -> float:
    ref = words(reference)
    return levenshtein(ref, words(hypothesis)) / max(1, len(ref))


def cer(reference: str, hypothesis: str) -> float:
    ref = chars(reference)
    return levenshtein(ref, chars(hypothesis)) / max(1, len(ref))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=pathlib.Path, required=True)
    parser.add_argument("--wavs", type=pathlib.Path, required=True)
    parser.add_argument("--out", type=pathlib.Path, required=True)
    args = parser.parse_args()

    rows = list(csv.DictReader(args.manifest.open(encoding="utf-8")))
    required = {"id", "language", "type", "reference", "hypothesis"}
    if not rows or not required <= set(rows[0]):
        print("FAIL: manifest needs id,language,type,reference,hypothesis columns", file=sys.stderr)
        return 2

    wavs = {p.name for p in args.wavs.glob("*.wav")}
    by_key = defaultdict(lambda: {"count": 0, "blanks": 0, "wer": [], "cer": []})
    items = []
    unsaid = 0
    for row in rows:
        reference = row["reference"].strip()
        hypothesis = row["hypothesis"].strip()
        if not reference:
            unsaid += 1
            continue
        key = f"{row['language']}/{row['type']}"
        bucket = by_key[key]
        bucket["count"] += 1
        if not hypothesis:
            bucket["blanks"] += 1
            items.append({"id": row["id"], "language": row["language"], "type": row["type"],
                          "blank": True, "wer": None, "cer": None})
            continue
        w = wer(reference, hypothesis)
        c = cer(reference, hypothesis)
        bucket["wer"].append(w)
        bucket["cer"].append(c)
        items.append({"id": row["id"], "language": row["language"], "type": row["type"],
                      "blank": False, "wer": round(w, 4), "cer": round(c, 4)})

    summary = {}
    for key, bucket in sorted(by_key.items()):
        summary[key] = {
            "utterances": bucket["count"],
            "blanks": bucket["blanks"],
            "blankRate": round(bucket["blanks"] / max(1, bucket["count"]), 4),
            "werMean": round(sum(bucket["wer"]) / len(bucket["wer"]), 4) if bucket["wer"] else None,
            "cerMean": round(sum(bucket["cer"]) / len(bucket["cer"]), 4) if bucket["cer"] else None,
        }

    report = {
        "manifest": str(args.manifest),
        "wavFiles": len(wavs),
        "rowsWithoutReference": unsaid,
        "note": "WER/CER from the text filled in the manifest; blank = no inserted text. "
                "This is not an acoustic model benchmark by itself: keep the WAVs with the report.",
        "summary": summary,
        "items": items,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"{'corpus':<14}{'utt':>4}{'blank':>7}{'blankRate':>11}{'WER':>8}{'CER':>8}")
    for key, s in summary.items():
        print(f"{key:<14}{s['utterances']:>4}{s['blanks']:>7}{s['blankRate']:>11.3f}"
              f"{(s['werMean'] if s['werMean'] is not None else float('nan')):>8.3f}"
              f"{(s['cerMean'] if s['cerMean'] is not None else float('nan')):>8.3f}")
    print(f"WROTE {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
