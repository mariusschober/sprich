| Metric | Canary (Whisper LID + Canary) | Parakeet TDT 0.6B v3 |
|---|---:|---:|
| Overall WER (mean per clip) | 0.109 | 0.071 |
| Overall CER (mean per clip) | 0.072 | 0.030 |
| EN WER | 0.229 | 0.084 |
| EN CER | 0.188 | 0.040 |
| EN blank / 50 | 7 | 0 |
| DE WER | 0.066 | 0.096 |
| DE CER | 0.028 | 0.036 |
| DE blank / 50 | 0 | 0 |
| ES WER | 0.061 | 0.046 |
| ES CER | 0.024 | 0.016 |
| ES blank / 50 | 0 | 0 |
| FR WER | 0.081 | 0.059 |
| FR CER | 0.049 | 0.029 |
| FR blank / 50 | 0 | 0 |
| LID accuracy | 1.000 | NOT MEASURED (no LID output) |
| Wrong-language rate (shared LID gate) | 0.000 | same gate; internal detection NOT MEASURED |
| p50 decode ms | 1651 | 1177 |
| p95 decode ms | 3994 | 2709 |
| Load ms | 1656 | 3027 |
| Model download bytes | 270,016,189 (LID+Canary) | 487,170,055 |
| Installed bytes | 296 MB extracted | 671 MB extracted |
| Warm PSS KiB | before 166343 / after Canary 565900 | after Parakeet 1248661 |
| Silence/noise clips with non-blank text | 0/20 | 0/20 |

Decision rule, applied mechanically:
- PASS: overall WER at least 10% lower
- FAIL: no language worse than Canary by >5% relative
- PASS: wrong-language rate <= Canary's
- FAIL: warm PSS <= 700 MB
- PASS: p50 decode <= 1.2 s
- PASS: 15-minute run without PSS growth
- languages beyond 5% relative: de
- PSS final/first ratio over 15 min: 1.060

**Decision: Canary remains Automatic; Parakeet stays out of production.**
