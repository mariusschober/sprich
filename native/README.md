# Recognition-only Android runtime

Sprich uses sherpa-onnx 1.13.6 with ONNX Runtime 1.27.1, ARM64, Android API 26+. The upstream all-purpose AAR includes unused text-to-speech code (including GPLv3 eSpeak NG), redundant C/C++ API libraries, and other ABIs. Sprich builds its own JNI with TTS and diarization disabled and packages only `libsherpa-onnx-jni.so` and `libonnxruntime.so`.

Run `ANDROID_SDK_ROOT=/path/to/sdk scripts/build-native-runtime.sh` with Python 3.11+, NDK 27.0.12077973 and SDK CMake 3.22.1 installed. The script checks source, Java wrapper AAR and ONNX Runtime archive hashes, keeps native debug information outside the AAR, and uses 16 KB linker flags. The JNI source is unmodified. Build paths/toolchain differences can change the resulting bytes; the checked-in artifact has the hashes below. Updating it requires native/device acceptance and updating the pins.

| Input | SHA-256 |
| --- | --- |
| sherpa-onnx v1.13.6 source tarball | `78f5d10f957d2de1867a1e08395e9ec2ec388911c853dd141887396667f3ff34` |
| upstream sherpa-onnx-1.13.6.aar | `0012d9a28f15bd6fb966b62b70a75da3990512fdccce28b83098248ce4be1698` |
| ONNX Runtime Android 1.27.1 ZIP | `defade26209f72cf4fa9769b18052c842833d6bef12924595d26f03b995548ca` |
| upstream Java classes.jar | `b6df872b28c2bdda146361be664ea42ad4ad70987f0d37ce6ec8115519f410ec` |
| AAR libonnxruntime.so | `dc5e4c172b1be9e530c6a62ad8f1be3e0a911cabdee6195abf28dab72477e194` |
| AAR recognition-only libsherpa-onnx-jni.so | `7a58931c7324c077c663b0c90eaa9863061d80bd6c990913399da1696456b9d9` |
| sherpa-onnx-1.13.6-asr-arm64.aar | `87897d9bc5c74356404b9c14e01f6478e46ddff3a6bc6932b5fb7affea318f6b` |

Linked source dependencies: kaldi-native-fbank 1.22.3, kaldi-decoder 0.3.0, kaldifst 1.8.0, OpenFst 1.8.5-2026-07-09, simple-sentencepiece 0.7, Eigen 5.0.1, nlohmann/json 3.12.0, KissFFT `febd4caeed32e33ad8b2e0bb5ea77542c40f18ec`, and the NDK libc++/libc++abi. Their archive hashes are pinned by upstream CMake. Full license texts are bundled in `app/src/main/assets/THIRD_PARTY_NOTICES.txt`; the resolved JVM dependency inventory is in `licenses/runtime-dependencies.tsv`.

F-Droid status: **BLOCKED: the ONNX Runtime binary and Java wrapper are still obtained as upstream prebuilts; this script does not establish a complete source-only F-Droid build chain.** Model licensing also needs separate review. This limitation is not a Play or direct-download runtime test result.

AGP strips the AAR's ONNX library when packaging the release APK; the measured APK bytes hash to `892bde5701ea47edffb3f1cc070f5bab690fccfca40e11baaed7b252084af477`. Check both the input AAR and final APK rather than assuming identical bytes.

The bundled Silero speech detector uses this existing JNI/ONNX runtime. Its pinned model and upstream license are recorded in [docs/MODELS.md](../docs/MODELS.md); no additional runtime, service or network request is introduced.
