#!/usr/bin/env python3
"""Extract a fixed FLEURS test subset (CC-BY-4.0) plus silence/noise clips for the bakeoff."""
import argparse, hashlib, json, pathlib, wave
import pyarrow.parquet as pq

LANGS = {"en_us": "en", "de_de": "de", "es_419": "es", "fr_fr": "fr"}
parser = argparse.ArgumentParser()
parser.add_argument("--fleurs", type=pathlib.Path, required=True)
parser.add_argument("--out", type=pathlib.Path, required=True)
parser.add_argument("--per-language", type=int, default=50)
parser.add_argument("--acoustic", type=int, default=20)
a = parser.parse_args()
wav_dir = a.out / "wav"; wav_dir.mkdir(parents=True, exist_ok=True)
clips = []
for config, lang in LANGS.items():
    table = pq.read_table(a.fleurs / f"{config}-test.parquet",
                          columns=["id", "transcription", "num_samples", "audio"])
    rows = sorted(table.to_pylist(), key=lambda r: (int(r["id"]), str(r["audio"]["path"])))[: a.per_language]
    for row in rows:
        raw = row["audio"]["bytes"]
        # FLEURS `id` repeats across rows; the audio file hash in `path` is unique.
        unique = str(row["audio"]["path"]).rsplit("/", 1)[-1].removesuffix(".wav")
        name = f"{config}-{unique}.wav"
        (wav_dir / name).write_bytes(raw)
        clips.append({
            "id": name[:-4],
            "language": lang,
            "config": config,
            "fleurs_id": int(row["id"]),
            "fleursAudioPath": str(row["audio"]["path"]).rsplit("/", 1)[-1],
            "reference": row["transcription"].strip(),
            "audio": name,
            "sha256": hashlib.sha256(raw).hexdigest(),
            "numSamples": int(row["num_samples"]),
            "durationMs": round(int(row["num_samples"]) * 1000 / 16000),
            "acousticSubset": False,
        })
# fixed acoustic subset: first N per language after sorting by id
by_lang = {}
for clip in clips:
    by_lang.setdefault(clip["language"], []).append(clip)
for lang, items in by_lang.items():
    for index, clip in enumerate(items):
        clip["acousticSubset"] = index < a.acoustic
# synthetic silence/noise clips (not FLEURS): deterministic PCM, never committed as audio
def wav_bytes(samples, path):
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
        w.writeframes(samples.tobytes())
import math, random
rng = random.Random(20261025)
for index in range(20):
    path = wav_dir / f"synthetic-{index:02d}.wav"
    n = 32000
    if index < 10:
        samples = [0] * n  # digital silence, 2 s
        kind = "silence"
    else:
        amp = 300 if index < 15 else 900
        samples = [max(-32768, min(32767, int(rng.gauss(0, amp)))) for _ in range(n)]
        kind = "noise"
    import array
    wav_bytes(array.array("h", samples), path)
    clips.append({
        "id": f"synthetic-{index:02d}", "language": "none", "config": "synthetic",
        "reference": "", "audio": path.name,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "numSamples": n, "durationMs": 2000, "acousticSubset": False, "kind": kind,
    })
manifest = {
    "corpus": "FLEURS test split (google/fleurs, CC-BY-4.0) + synthetic silence/noise",
    "license": "CC-BY-4.0",
    "licenseUrl": "https://huggingface.co/datasets/google/fleurs",
    "attribution": "FLEURS: Conneau et al., Google, CC-BY-4.0. Synthetic clips generated locally.",
    "selection": f"first {a.per_language} test rows per language config, ascending dataset id; "
                 f"acoustic subset = first {a.acoustic} per language",
    "audioCommitted": False,
    "clips": clips,
}
(a.out / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False))
print("clips", len(clips), "->", a.out / "manifest.json")
