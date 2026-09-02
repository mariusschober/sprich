#!/usr/bin/env bash
# Build Sprich's ARM64 recognition-only JNI. No model weights or TTS code are included.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Set ANDROID_SDK_ROOT or ANDROID_HOME}}"
NDK="$SDK/ndk/27.0.12077973"
CMAKE="$SDK/cmake/3.22.1/bin/cmake"
NINJA="$SDK/cmake/3.22.1/bin/ninja"
WORK="${SPRICH_NATIVE_WORK_DIR:-$ROOT/native/build}"
case "$(uname -s)" in Darwin) HOST=darwin-x86_64;; Linux) HOST=linux-x86_64;; *) echo 'Unsupported build host' >&2; exit 1;; esac
LLVM="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
mkdir -p "$WORK" "$WORK/symbols/arm64-v8a"
fetch() {
  local url="$1" path="$2" expected="$3"
  if [ ! -f "$path" ]; then curl --fail --location --proto '=https' --proto-redir '=https' --retry 3 "$url" -o "$path"; fi
  python3 - "$path" "$expected" <<'PY'
import hashlib,sys
with open(sys.argv[1],'rb') as f: actual=hashlib.file_digest(f,'sha256').hexdigest()
if actual != sys.argv[2]: raise SystemExit('SHA-256 mismatch: '+sys.argv[1])
PY
}
fetch https://codeload.github.com/k2-fsa/sherpa-onnx/tar.gz/refs/tags/v1.13.6 "$WORK/source.tar.gz" 78f5d10f957d2de1867a1e08395e9ec2ec388911c853dd141887396667f3ff34
fetch https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.6/sherpa-onnx-1.13.6.aar "$WORK/upstream.aar" 0012d9a28f15bd6fb966b62b70a75da3990512fdccce28b83098248ce4be1698
fetch https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.27.1/onnxruntime-android-1.27.1.zip "$WORK/ort.zip" defade26209f72cf4fa9769b18052c842833d6bef12924595d26f03b995548ca
if [ ! -d "$WORK/sherpa-onnx-1.13.6" ]; then tar -xzf "$WORK/source.tar.gz" -C "$WORK"; fi
if [ ! -d "$WORK/ort" ]; then mkdir "$WORK/ort"; unzip -q "$WORK/ort.zip" -d "$WORK/ort"; fi
# Upstream pins every fetched C++ dependency by SHA-256. TLS verification remains enabled.
# macOS SDK CMake needs an explicit system CA bundle.
TLS_ARGS=()
if [ "$HOST" = darwin-x86_64 ]; then TLS_ARGS=(-DCMAKE_TLS_CAINFO=/etc/ssl/cert.pem); fi
SHERPA_ONNXRUNTIME_LIB_DIR="$WORK/ort/jni/arm64-v8a" \
SHERPA_ONNXRUNTIME_INCLUDE_DIR="$WORK/ort/headers" \
"$CMAKE" -S "$WORK/sherpa-onnx-1.13.6" -B "$WORK/build" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$NINJA" -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo -DBUILD_SHARED_LIBS=ON -DSHERPA_ONNX_ENABLE_TTS=OFF \
  -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF -DSHERPA_ONNX_ENABLE_PYTHON=OFF \
  -DSHERPA_ONNX_ENABLE_TESTS=OFF -DSHERPA_ONNX_ENABLE_CHECK=OFF -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF \
  -DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF -DSHERPA_ONNX_ENABLE_BINARY=OFF -DSHERPA_ONNX_ENABLE_JNI=ON \
  -DSHERPA_ONNX_ENABLE_C_API=OFF -DCMAKE_TLS_VERIFY=ON "${TLS_ARGS[@]}" \
  '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384,-z,common-page-size=16384' \
  -DCMAKE_INSTALL_PREFIX="$WORK/install"
"$CMAKE" --build "$WORK/build" --target sherpa-onnx-jni -j "${SPRICH_NATIVE_JOBS:-4}"
"$LLVM/llvm-objcopy" --only-keep-debug "$WORK/build/lib/libsherpa-onnx-jni.so" "$WORK/symbols/arm64-v8a/libsherpa-onnx-jni.so.dbg"
"$LLVM/llvm-strip" --strip-unneeded -o "$WORK/libsherpa-onnx-jni.so" "$WORK/build/lib/libsherpa-onnx-jni.so"
python3 - "$WORK" "$ROOT/app/libs/sherpa-onnx-1.13.6-asr-arm64.aar" <<'PY'
from pathlib import Path
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED
import hashlib,sys
work=Path(sys.argv[1])
with ZipFile(work/'upstream.aar') as z:
    data={n:z.read(n) for n in z.namelist() if not n.startswith('jni/') and not n.endswith('/')}
    data['jni/arm64-v8a/libonnxruntime.so']=z.read('jni/arm64-v8a/libonnxruntime.so')
assert hashlib.sha256(data['jni/arm64-v8a/libonnxruntime.so']).hexdigest()=='dc5e4c172b1be9e530c6a62ad8f1be3e0a911cabdee6195abf28dab72477e194'
data['jni/arm64-v8a/libsherpa-onnx-jni.so']=(work/'libsherpa-onnx-jni.so').read_bytes()
with ZipFile(sys.argv[2],'w',compression=ZIP_DEFLATED,compresslevel=9) as z:
    for name,value in sorted(data.items()):
        info=ZipInfo(name,(1980,1,1,0,0,0));info.compress_type=ZIP_DEFLATED;info.external_attr=0o644<<16
        z.writestr(info,value,compresslevel=9)
print('Built',sys.argv[2])
print('SHA-256',hashlib.sha256(Path(sys.argv[2]).read_bytes()).hexdigest())
PY
"$LLVM/llvm-readelf" -l -d "$WORK/libsherpa-onnx-jni.so"
