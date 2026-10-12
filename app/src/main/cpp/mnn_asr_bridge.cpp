// mnn_asr_bridge.cpp
//
// JNI bridge: Kotlin MnnAsrBackend <-> MNN Omni engine (Qwen3-ASR-0.6B INT8).
//
// Adapted from the official MNN demo `apps/Android/MnnLlmChat`
// (llm_mnn_jni.cpp / llm_session.cpp, Apache-2.0), trimmed to the single
// blocking ASR call this app needs.
//
// Audio input follows the official path: the Kotlin side writes a 16kHz mono
// WAV to the app cache dir and we pass `<audio>path</audio>` as the prompt;
// the Omni engine parses the tag itself and runs RMS normalize -> kaldi fbank
// -> audio encoder -> LLM decoder internally (see MNN transformers/llm/engine).
//
// Build requirements (see README "MNN native 构建"):
//   - Android NDK r27+, CMake 3.22+
//   - MNN source checkout built for arm64-v8a with LLM + audio support
//     (-DMNN_BUILD_LLM=true -DLLM_SUPPORT_AUDIO=true -DMNN_BUILD_AUDIO=true)
//   - libMNN.so copied to app/src/main/jniLibs/arm64-v8a/
//
// JNI mapping (class com.icegps.voiceime.service.asr.MnnAsrBackend):
//   nativeInit(modelDir, runtimeConfigJson) -> jlong (0 = failed, exception thrown)
//   nativeTranscribe(handle, wavPath, maxNewTokens) -> jstring (transcription)
//   nativeRelease(handle)

#include <jni.h>

#include <string>
#include <sstream>
#include <android/log.h>

#include "llm/llm.hpp"

#define LOG_TAG "VoiceIME-MNN"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using MNN::Transformer::Llm;

namespace {

std::string JstringToString(JNIEnv* env, jstring s) {
    if (s == nullptr) {
        return "";
    }
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) {
        env->ReleaseStringUTFChars(s, c);
    }
    return out;
}

void ThrowIllegalState(JNIEnv* env, const std::string& msg) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) {
        env->ThrowNew(cls, msg.c_str());
    }
}

std::string Trim(const std::string& s) {
    size_t b = s.find_first_not_of(" \t\r\n");
    if (b == std::string::npos) {
        return "";
    }
    size_t e = s.find_last_not_of(" \t\r\n");
    return s.substr(b, e - b + 1);
}

// One session per native handle. MnnAsrBackend guarantees transcribe() is
// never called concurrently (single transcribe thread + initLock).
struct AsrSession {
    Llm* llm = nullptr;
};

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_icegps_voiceime_service_asr_MnnAsrBackend_nativeInit(JNIEnv* env, jobject /*thiz*/,
                                                             jstring modelDir,
                                                             jstring runtimeConfigJson) {
    const std::string dir = JstringToString(env, modelDir);
    // LlmConfig accepts either the config.json path or the model directory;
    // passing the explicit file path is unambiguous.
    const std::string configPath = dir + "/config.json";
    LOGI("createLLM: %s", configPath.c_str());

    Llm* llm = Llm::createLLM(configPath);
    if (llm == nullptr) {
        ThrowIllegalState(env, "Llm::createLLM returned null for " + configPath +
                                   " (config.json missing or invalid)");
        return 0;
    }
    // Runtime overrides from Kotlin (backend_type=cpu, thread_num=4).
    // Default backend is already CPU; this pins it explicitly because mobile
    // GPU decode is slower (OpenCL 15-60x) and Vulkan hangs (pre-research §5).
    const std::string extra = JstringToString(env, runtimeConfigJson);
    if (!extra.empty()) {
        llm->set_config(extra);
    }
    if (!llm->load()) {
        LOGE("llm->load() failed for %s", configPath.c_str());
        Llm::destroy(llm);
        ThrowIllegalState(env, "MNN model load() failed (see logcat MNN/Omni logs); " +
                                   std::string("check llm.mnn/audio.mnn/llm_config.json in ") + dir);
        return 0;
    }
    auto* session = new AsrSession();
    session->llm = llm;
    LOGI("model loaded, session=%p", session);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jstring JNICALL
Java_com_icegps_voiceime_service_asr_MnnAsrBackend_nativeTranscribe(JNIEnv* env, jobject /*thiz*/,
                                                                   jlong handle, jstring wavPath,
                                                                   jint maxNewTokens) {
    auto* session = reinterpret_cast<AsrSession*>(handle);
    if (session == nullptr || session->llm == nullptr) {
        ThrowIllegalState(env, "nativeTranscribe called with invalid handle");
        return nullptr;
    }
    const std::string wav = JstringToString(env, wavPath);
    // Omni's tokenizer_encode parses <audio>path</audio> itself.
    const std::string prompt = "<audio>" + wav + "</audio>";

    session->llm->reset();  // clear KV cache / history from previous utterance
    std::ostringstream oss;
    session->llm->response(prompt, &oss, "<|endoftext|>", static_cast<int>(maxNewTokens));

    const MNN::Transformer::LlmContext* ctx = session->llm->getContext();
    if (ctx) {
        LOGI("asr done: audio=%.1fs prefill=%lldms decode=%lldms tokens=%d",
             ctx->audio_input_s, ctx->prefill_us / 1000, ctx->decode_us / 1000, ctx->gen_seq_len);
    }
    const std::string text = Trim(oss.str());
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT void JNICALL
Java_com_icegps_voiceime_service_asr_MnnAsrBackend_nativeRelease(JNIEnv* /*env*/, jobject /*thiz*/,
                                                                 jlong handle) {
    auto* session = reinterpret_cast<AsrSession*>(handle);
    if (session != nullptr) {
        if (session->llm != nullptr) {
            Llm::destroy(session->llm);
        }
        delete session;
        LOGI("session released");
    }
}

}  // extern "C"
