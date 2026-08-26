cmake_minimum_required(VERSION 3.22)
project(sprich_native)

# Canary focus — Whisper deleted per user request. No native whisper build.
# Keep a minimal valid CMake for Gradle externalNativeBuild. Canary uses sherpa-onnx AAR (no CMake).
# 16KB page size still enforced for any future native code.
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
add_link_options("-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
