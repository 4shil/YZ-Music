# Native analysis front end for Automix.
#
# Phase 1: whole-track DSP analysis (tempo/key/energy/structure via
# autocorrelation, no ML model). Phase 2 adds the log-mel front end the
# Beat This! ONNX beat/downbeat model reads (mel_spectrogram.cpp / mel_jni.cpp).
# Phase 3 adds the linear-frequency STFT the open-unmix vocal-separation model
# reads (vocal_spectrogram.cpp / vocal_jni.cpp). Both models themselves run
# through ONNX Runtime on the Kotlin side, not here. The shared analyzer
# sources live under the repo's root `native/` tree rather than here, so a
# later phase adding a Rubber Band-based transition renderer can add sources
# to that same tree without duplicating this module.

cmake_minimum_required(VERSION 3.22.1)
# C as well as CXX: the sources today are all C++, but a future native
# decoder (DSD/DST are the obvious candidates) would be C, and declaring
# only CXX here fails at configure time rather than at compile time.
project(yzmusic_analysis LANGUAGES C CXX)

get_filename_component(YZMUSIC_NATIVE_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native"
    ABSOLUTE)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(yzmusic_analysis SHARED
    ${YZMUSIC_NATIVE_DIR}/analyzer/audio_analysis.cpp
    ${YZMUSIC_NATIVE_DIR}/analyzer/tempo_analysis.cpp
    ${YZMUSIC_NATIVE_DIR}/analyzer/resampler.cpp
    ${YZMUSIC_NATIVE_DIR}/analyzer/mel_spectrogram.cpp
    ${YZMUSIC_NATIVE_DIR}/analyzer/vocal_spectrogram.cpp
    jni/analysis_jni.cpp
    jni/mel_jni.cpp
    jni/vocal_jni.cpp
)

target_include_directories(yzmusic_analysis PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${YZMUSIC_NATIVE_DIR}
)

# -ffast-math is deliberately NOT enabled: the analyzer's log/mel-adjacent
# arithmetic (see audio_analysis.cpp) is tuned against exact IEEE behaviour.
target_compile_options(yzmusic_analysis PRIVATE -O3 -fvisibility=hidden)

# 16 KB page-size support (Android 15+ 16 KB kernels, Play requirement):
# align LOAD segments to 16 KB. A superset of 4 KB alignment, so 4 KB devices
# are unaffected. APK-side alignment of uncompressed .so files is handled by
# AGP; this covers the ELF inside them.
#
# Without it the library is 4 KB-aligned only and fails to load outright on a
# 16 KB-page device — which silently takes out the whole analyzer rather than
# surfacing as a crash, because every System.loadLibrary call is wrapped in a
# runCatching and cached into an `available` flag. Automix then falls back to
# the autocorrelation grid with the DSP vocal heuristic and nothing says why.
target_link_options(yzmusic_analysis PRIVATE "-Wl,-z,max-page-size=16384")

find_library(log-lib log)
target_link_libraries(yzmusic_analysis ${log-lib})
