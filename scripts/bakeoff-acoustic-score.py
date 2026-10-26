#!/usr/bin/env python3
"""Score the acoustic bake-off subset (Mac speaker -> device microphone -> production IME -> editor).

Input: the JSON written by scripts/bakeoff-acoustic.py. Normalization matches
scripts/bakeoff-score.py (NFKC, lowercase, punctuation stripped, whitespace collapsed; WER words,
CER characters). Reports per-language WER/CER, insertion rate (non-empty field text) and counts;
the production baseline is the only engine measured acoustically in 1.0.
"""
import argparse
import json
import pathlib
import re
import statistics
import unicodedata

PUNCT = re.compile(r"[^\w\s]", re.UNICODE)


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFKC", text).lower()
    return " ".join(PUNCT.sub(" ", text).split())


def levenshtein(a, b) -> int:
    if len(a) < len(b):
        a, b = b, a
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[len(b)]


def wer(ref: str, hyp: str) -> float:
    r = normalize(ref).split()
    return levenshtein(r, normalize(hyp).split()) / max(1, len(r))


def cer(ref: str, hyp: str) -> float:
    r = list(normalize(ref).replace(" ", ""))
    h = list(normalize(hyp).replace(" ", ""))
    return levenshtein(r, h) / max(1, len(r))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", type=pathlib.Path, required=True)
    parser.add_argument("--out", type=pathlib.Path)
    args = parser.parse_args()
    data = json.loads(args.results.read_text())
    rows = data["results"]
    per_lang = {}
    for lang in ("en", "de", "es", "fr"):
        selected = [r for r in rows if r["language"] == lang]
        if not selected:
            continue
        inserted = [r for r in selected if r["inserted"].strip()]
        per_lang[lang] = {
            "clips": len(selected),
            "inserted": len(inserted),
            "wer": statistics.fmean([wer(r["reference"], r["inserted"]) for r in selected]),
            "cer": statistics.fmean([cer(r["reference"], r["inserted"]) for r in selected]),
            "werInserted": statistics.fmean([wer(r["reference"], r["inserted"]) for r in inserted]) if inserted else None,
        }
    lines = ["| Language | Clips | Inserted | WER (all) | WER (inserted only) | CER (all) |",
             "|---|---:|---:|---:|---:|---:|"]
    for lang, s in per_lang.items():
        lines.append(f"| {lang.upper()} | {s['clips']} | {s['inserted']} | {s['wer']:.3f} | "
                     f"{(f'{s['werInserted']:.3f}' if s['werInserted'] is not None else 'n/a')} | {s['cer']:.3f} |")
    total = len(rows)
    inserted_total = sum(1 for r in rows if r["inserted"].strip())
    overall_wer = statistics.fmean([wer(r["reference"], r["inserted"]) for r in rows]) if rows else 0.0
    lines += ["", f"Insertion rate: {inserted_total}/{total}. Overall WER (all clips): {overall_wer:.3f}.",
              f"Method: {data.get('method')}. Engine: {data.get('engine')}. "
              f"Parakeet acoustic: {data.get('parakeetAcoustic')}."]
    text = "\n".join(lines)
    print(text)
    if args.out:
        args.out.write_text(text + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
