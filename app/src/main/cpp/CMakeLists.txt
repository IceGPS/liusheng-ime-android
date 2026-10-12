# CMakeLists.txt — voiceime_mnn: JNI bridge for MNN Omni ASR (Qwen3-ASR-0.6B).
#
# Official approach (same as alibaba/MNN apps/Android/MnnLlmChat):
#   1. Build MNN itself for Android arm64 with LLM + audio support.
#   2. Copy the resulting libMNN.so into app/src/main/jniLibs/arm64-v8a/
#      (auto-packaged into the APK; gitignored like the official demo).
#   3. This file compiles only our small bridge (mnn_asr_bridge.cpp) against
#      the MNN headers and links the prebuilt libMNN.so.
#
# MNN build command (run inside the MNN source checkout, needs Android NDK):
#   ./project/android/build_64.sh "-DMNN_BUILD_LLM=true -DLLM_SUPPORT_AUDIO=true \
#     -DMNN_BUILD_AUDIO=true -DMNN_LOW_MEMORY=true -DMNN_CPU_WEIGHT_DEQUANT_GEMM=true \
#     -DMNN_SUPPORT_TRANSFORMER_FUSE=true -DMNN_ARM82=true -DMNN_USE_LOGCAT=true \
#     -DCMAKE_SHARED_LINKER_FLAGS='-Wl,-z,max-page-size=16384' -DCMAKE_INSTALL_PREFIX=."
#   # then: cp <MNN>/project/android/build_64/lib/libMNN.so \
#   #          app/src/main/jniLibs/arm64-v8a/
#
# Wire-up: app/build.gradle.kts enables externalNativeBuild only when an NDK
# is present; pass -DMNN_ROOT=/path/to/MNN via local.properties (mnnRoot) or
# the MNN_ROOT environment variable.
cmake_minimum_required(VERSION 3.22.1)

project("voiceime_mnn")

# --- MNN source checkout (headers only; the .so is prebuilt) ---
if(NOT DEFINED MNN_ROOT OR "${MNN_ROOT}" STREQUAL "")
    if(DEFINED ENV{MNN_ROOT})
        set(MNN_ROOT "$ENV{MNN_ROOT}")
    else()
        message(FATAL_ERROR
            "MNN_ROOT not set. Check out alibaba/MNN (pinned version, see README) "
            "and set MNN_ROOT=/path/to/MNN (env var or -DMNN_ROOT=...).")
    endif()
endif()
message(STATUS "MNN_ROOT: ${MNN_ROOT}")

add_library(voiceime_mnn SHARED mnn_asr_bridge.cpp)

target_include_directories(voiceime_mnn PRIVATE
    "${MNN_ROOT}/include"
    "${MNN_ROOT}/transformers/llm/engine/include"
)

# --- prebuilt libMNN.so (must contain the LLM/Omni engine) ---
# Search order: vendored jniLibs copy, then the MNN build tree.
set(LIBMNN_CANDIDATES
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libMNN.so"
    "${MNN_ROOT}/project/android/build_64/libMNN.so"
)
set(LIBMNN_PATH "")
foreach(cand IN LISTS LIBMNN_CANDIDATES)
    if(EXISTS "${cand}")
        set(LIBMNN_PATH "${cand}")
        break()
    endif()
endforeach()
if("${LIBMNN_PATH}" STREQUAL "")
    message(FATAL_ERROR
        "libMNN.so not found. Build MNN for Android arm64 with "
        "-DMNN_BUILD_LLM=true -DLLM_SUPPORT_AUDIO=true -DMNN_BUILD_AUDIO=true "
        "and copy libMNN.so to app/src/main/jniLibs/arm64-v8a/ (see README).")
endif()
message(STATUS "libMNN.so: ${LIBMNN_PATH}")

add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES IMPORTED_LOCATION "${LIBMNN_PATH}")

find_library(LOG_LIB log)

target_link_libraries(voiceime_mnn
    MNN
    ${LOG_LIB}
)

# 16KB page size (Android 15+ requirement, same as the official demo).
target_link_options(voiceime_mnn PRIVATE "-Wl,-z,max-page-size=16384")

# Keep the JNI symbols; the bridge has no other exports to strip concerns about.
set_target_properties(voiceime_mnn PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)
