cmake_minimum_required(VERSION 3.22)
project(sprich_native)

# Real whisper.cpp mmap integration — replaces dummy
# Options before add_subdirectory (whisper.cpp uses these)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Add whisper.cpp (provides whisper, ggml, ggml-base, ggml-cpu)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/whisper whisper_build)

add_library(sprich_whisper SHARED whisper_jni.cpp)
add_library(sprich_nemotron SHARED dummy_nemotron.cpp)

target_link_libraries(sprich_whisper PRIVATE whisper ggml ggml-base ggml-cpu log android)
target_include_directories(sprich_whisper PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/whisper/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/whisper/ggml/include
)

target_compile_features(sprich_whisper PRIVATE cxx_std_17)
target_compile_features(sprich_nemotron PRIVATE cxx_std_17)

# ARM optimizations for release + 16KB page support (Android 15+ mandatory for target 36)
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -ffast-math -march=armv8-a")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
add_link_options("-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
target_link_options(sprich_whisper PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
target_link_options(sprich_nemotron PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
