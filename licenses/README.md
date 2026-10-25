# Licenses and notices

The app has a searchable, offline reader with one entry per component or related library family. Full original license texts are opened only when requested. The plain-text distribution file includes each identical document once. Hashes and build evidence belong in this directory, not in the reading experience.

`catalog.json` records the exact components, versions, source locations, document hashes and runtime coordinates. `texts/` contains complete source documents. `runtime-dependencies.tsv` is resolved by `:app:writeReleaseDependencyInventory`, including `coreLibraryDesugaring`: that library injects code into the APK even though it is absent from `releaseRuntimeClasspath`.

After changing a dependency, update and review the inventory and catalog, then run `python3 scripts/generate-notices.py`. CI runs `--check` and compares the resolved inventory. These checks establish coverage and byte consistency; they do not certify legal permission.

## Corrections in this version

- Added the previously omitted Java compatibility library, `desugar_jdk_libs` 2.1.4, including GPL 2, the Classpath exception, assembly exception, additional licensing information and source provenance.
- Replaced truncated LLVM/C++ notices with the complete notice from NDK 27.0.12077973.
- Removed obsolete attribution to unused whisper.cpp and Nemotron code/models.
- Preserved upstream copyright notices, Apache NOTICE content, the OkHttp Public Suffix List notice and the full ONNX Runtime notice bundle. The latter also covers other upstream platforms; retaining it is not a claim that every listed component is linked into Sprich.
- Separated the MIT app license, software dependency licenses and downloaded model terms. Canary's CC BY 4.0 attribution identifies NVIDIA and the ONNX/INT8 adaptation.

Standard license examples such as `[yyyy]` in Apache's appendix are part of the original legal text. They are not unfinished Sprich attribution and must not be silently rewritten.

## Public distribution requirements

**FastConformer removed from the production configuration (T3, 2026-10-25).** The model card for `stt_multilingual_fastconformer_hybrid_large_pc_blend_eu` 1.21.0 points to [NGC terms](https://ngc.nvidia.com/legal/terms), not Apache 2.0, and those terms bar redistribution without a separate NVIDIA agreement. The original terms JSON is retained under `provenance/` as the record of why the model is excluded; the NGC license text is no longer part of the shipped notices. Automatic now uses Whisper Tiny (language ID, MIT) + Canary 180M Flash (CC-BY-4.0); Accurate uses the same Canary. No NGC-licensed bytes are downloaded, installed or redistributed by Sprich.

**BLOCKED: corresponding-source delivery must accompany the first public binary release.** Desugared Java is derived from OpenJDK and includes GPL 2 code with the Classpath exception. This exception allows Sprich's independent application code to retain its MIT license; it does not remove the library's source-distribution obligations. Its exact source is [Google's 2.1.4 release commit](https://github.com/google/desugar_jdk_libs/tree/50d9c1fb3e85fa4c00161525a45484f712c1f003). Provide the complete source archive, its upstream build files and Sprich's build configuration alongside the binary, with matching release references. Do not substitute a vague written offer or assume a link will remain available indefinitely. Also preserve source availability for MPL-covered Eigen and Public Suffix List material.

Human review of the final package remains separate from these engineering checks. This file records concrete unresolved distribution requirements; the app does not display a claim of legal certification.
