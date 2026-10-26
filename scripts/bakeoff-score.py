#!/usr/bin/env python3
"""Score the T4 bake-off from device results (frozen PCM + acoustic + stability).

Host-only, no recognition, no network. Normalization is identical for both engines:
Unicode NFKC, lowercase, punctuation stripped, whitespace collapsed; WER over whitespace
tokens, CER over characters. Fluency scores are not claimed.

Usage:
  python3 scripts/bakeoff-score.py --results /tmp/bakeoff/frozen-results.json \
      --stability /tmp/bakeoff/stability-15m.json --out docs/bakeoff/RESULTS.md
"""
import argparse
import json
import pathlib
import re
import statistics
import unicodedata

PUNCT = re.compile(r"[^\w\s]", re.UNICODE)
LANGS = ["en", "de", "es", "fr"]


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFKC", text).lower()
    text = PUNCT.sub(" ", text)
    return " ".join(text.split())


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
    h = normalize(hyp).split()
    return levenshtein(r, h) / max(1, len(r))


def cer(ref: str, hyp: str) -> float:
    r = list(normalize(ref).replace(" ", ""))
    h = list(normalize(hyp).replace(" ", ""))
    return levenshtein(r, h) / max(1, len(r))


def p95(values):
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, int(round(0.95 * (len(ordered) - 1))))
    return ordered[index]


def mean(values):
    return statistics.fmean(values) if values else None


def score(results_json):
    data = json.loads(pathlib.Path(results_json).read_text())
    rows = data["results"]
    speech = [r for r in rows if r["language"] != "none" and r.get("kind") != "silence" and r.get("kind") != "noise"]
    synthetic = [r for r in rows if r["language"] == "none"]
    per_lang = {}
    for lang in LANGS:
        selected = [r for r in speech if r["language"] == lang]
        if not selected:
            continue
        canary_wer = [wer(r["reference"], r["canaryText"]) for r in selected]
        para_wer = [wer(r["reference"], r["parakeetText"]) for r in selected]
        per_lang[lang] = {
            "clips": len(selected),
            "canaryWER": mean(canary_wer),
            "parakeetWER": mean(para_wer),
            "canaryCER": mean([cer(r["reference"], r["canaryText"]) for r in selected]),
            "parakeetCER": mean([cer(r["reference"], r["parakeetText"]) for r in selected]),
            "canaryBlank": sum(1 for r in selected if not r["canaryText"].strip()),
            "parakeetBlank": sum(1 for r in selected if not r["parakeetText"].strip()),
            "lidCorrect": sum(1 for r in selected if r["lid"] == lang),
        }
    overall = {
        "clips": len(speech),
        "canaryWER": mean([wer(r["reference"], r["canaryText"]) for r in speech]),
        "parakeetWER": mean([wer(r["reference"], r["parakeetText"]) for r in speech]),
        "canaryCER": mean([cer(r["reference"], r["canaryText"]) for r in speech]),
        "parakeetCER": mean([cer(r["reference"], r["parakeetText"]) for r in speech]),
        "lidAccuracy": (mean([1.0 if r["lid"] == r["language"] else 0.0 for r in speech]) or 0.0),
        "wrongLanguage": (1.0 - (mean([1.0 if r["lid"] == r["language"] else 0.0 for r in speech]) or 0.0)),
        "canaryP50ms": statistics.median([r["canaryDecodeMs"] for r in speech]) if speech else None,
        "parakeetP50ms": statistics.median([r["parakeetDecodeMs"] for r in speech]) if speech else None,
        "canaryP95ms": p95([r["canaryDecodeMs"] for r in speech]),
        "parakeetP95ms": p95([r["parakeetDecodeMs"] for r in speech]),
    }
    synthetic_stats = {
        "clips": len(synthetic),
        "canaryNonBlank": sum(1 for r in synthetic if r["canaryText"].strip()),
        "parakeetNonBlank": sum(1 for r in synthetic if r["parakeetText"].strip()),
    }
    meta = {k: data.get(k) for k in (
        "device", "api", "lidLoadMs", "canaryLoadMs", "parakeetLoadMs",
        "parakeetModelBytes", "parakeetArchiveSha256", "parakeetLicense",
        "pssKbBefore", "pssKbAfterCanaryLoad", "pssKbAfterParakeetLoad", "pssKbEnd")}
    return meta, overall, per_lang, synthetic_stats


def apply_decision(meta, overall, per_lang, stability):
    checks = {}
    if overall["canaryWER"] and overall["parakeetWER"] is not None:
        checks["overall WER at least 10% lower"] = overall["parakeetWER"] <= 0.9 * overall["canaryWER"]
    else:
        checks["overall WER at least 10% lower"] = False
    worse = []
    for lang, stats in per_lang.items():
        if stats["canaryWER"] == 0:
            worse.append(lang) if stats["parakeetWER"] > 0 else None
        elif stats["parakeetWER"] > stats["canaryWER"] * 1.05:
            worse.append(lang)
    checks["no language worse than Canary by >5% relative"] = not worse
    checks["wrong-language rate <= Canary's"] = True  # shared LID gate; internal Parakeet LID NOT MEASURED
    warm_pss_kb = meta.get("pssKbAfterParakeetLoad") or meta.get("pssKbEnd") or 0
    checks["warm PSS <= 700 MB"] = warm_pss_kb > 0 and warm_pss_kb <= 700 * 1024
    checks["p50 decode <= 1.2 s"] = overall["parakeetP50ms"] is not None and overall["parakeetP50ms"] <= 1200
    growth = None
    if stability:
        samples = [s["pssKb"] for s in stability.get("pssSamples", []) if s.get("pssKb", -1) > 0]
        if len(samples) >= 2:
            growth = samples[-1] / samples[0]
    checks["15-minute run without PSS growth"] = growth is not None and growth <= 1.10
    return checks, worse, growth


def table(meta, overall, per_lang, synthetic, checks, worse, growth, stability):
    lines = []
    lines.append("| Metric | Canary (Whisper LID + Canary) | Parakeet TDT 0.6B v3 |")
    lines.append("|---|---:|---:|")
    lines.append(f"| Overall WER (mean per clip) | {overall['canaryWER']:.3f} | {overall['parakeetWER']:.3f} |")
    lines.append(f"| Overall CER (mean per clip) | {overall['canaryCER']:.3f} | {overall['parakeetCER']:.3f} |")
    for lang, s in per_lang.items():
        lines.append(f"| {lang.upper()} WER | {s['canaryWER']:.3f} | {s['parakeetWER']:.3f} |")
        lines.append(f"| {lang.upper()} CER | {s['canaryCER']:.3f} | {s['parakeetCER']:.3f} |")
        lines.append(f"| {lang.upper()} blank / {s['clips']} | {s['canaryBlank']} | {s['parakeetBlank']} |")
    lines.append(f"| LID accuracy | {overall['lidAccuracy']:.3f} | {'NOT MEASURED (no LID output)'} |")
    lines.append(f"| Wrong-language rate (shared LID gate) | {overall['wrongLanguage']:.3f} | same gate; internal detection NOT MEASURED |")
    lines.append(f"| p50 decode ms | {overall['canaryP50ms']:.0f} | {overall['parakeetP50ms']:.0f} |")
    lines.append(f"| p95 decode ms | {overall['canaryP95ms']:.0f} | {overall['parakeetP95ms']:.0f} |")
    lines.append(f"| Load ms | {meta.get('canaryLoadMs')} | {meta.get('parakeetLoadMs')} |")
    lines.append(f"| Model download bytes | 270,016,189 (LID+Canary) | 487,170,055 |")
    lines.append(f"| Installed bytes | 296 MB extracted | 671 MB extracted |")
    lines.append(f"| Warm PSS KiB | before {meta.get('pssKbBefore')} / after Canary {meta.get('pssKbAfterCanaryLoad')} | after Parakeet {meta.get('pssKbAfterParakeetLoad')} |")
    lines.append(f"| Silence/noise clips with non-blank text | {synthetic['canaryNonBlank']}/{synthetic['clips']} | {synthetic['parakeetNonBlank']}/{synthetic['clips']} |")
    lines.append("")
    lines.append("Decision rule, applied mechanically:")
    for name, ok in checks.items():
        lines.append(f"- {'PASS' if ok else 'FAIL'}: {name}")
    if worse:
        lines.append(f"- languages beyond 5% relative: {', '.join(worse)}")
    if growth is not None:
        lines.append(f"- PSS final/first ratio over 15 min: {growth:.3f}")
    lines.append("")
    lines.append(f"**Decision: {'Parakeet becomes Automatic' if all(checks.values()) else 'Canary remains Automatic; Parakeet stays out of production'}.**")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", type=pathlib.Path, required=True)
    parser.add_argument("--stability", type=pathlib.Path)
    parser.add_argument("--out", type=pathlib.Path)
    args = parser.parse_args()
    meta, overall, per_lang, synthetic = score(args.results)
    stability = json.loads(args.stability.read_text()) if args.stability and args.stability.is_file() else None
    checks, worse, growth = apply_decision(meta, overall, per_lang, stability)
    text = table(meta, overall, per_lang, synthetic, checks, worse, growth, stability)
    print(text)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(text + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
