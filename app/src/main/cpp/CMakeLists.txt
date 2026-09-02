cmake_minimum_required(VERSION 3.22)
project(sprich_native)

# 16KB page size enforcement for Play (Android 16, targetSdk 36). Every PT_LOAD must be 16384 aligned.
# Verify: llvm-readelf -l lib/arm64-v8a/*.so | grep LOAD (Align 16384)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
add_link_options("-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
