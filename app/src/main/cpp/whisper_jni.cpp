#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
#include <atomic>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::atomic<int> g_threads{4};

extern "C" {

JNIEXPORT jlong JNICALL Java_com_sprich_app_speech_whisper_WhisperJNI_nativeInit(JNIEnv* env, jobject, jstring jpath) {
    if (jpath == nullptr) return 0;
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return 0;
    std::string cppPath(path);
    env->ReleaseStringUTFChars(jpath, path);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    struct whisper_context* ctx = whisper_init_from_file_with_params(cppPath.c_str(), cparams);
    if (!ctx) {
        LOGE("whisper_init_from_file_with_params failed for %s", cppPath.c_str());
        return 0;
    }
    LOGI("whisper_init success %p threads=%d path=%s", ctx, g_threads.load(), cppPath.c_str());
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL Java_com_sprich_app_speech_whisper_WhisperJNI_nativeRelease(JNIEnv* env, jobject, jlong ctx) {
    if (ctx == 0) return;
    auto* wctx = reinterpret_cast<struct whisper_context*>(ctx);
    whisper_free(wctx);
    LOGI("whisper_free %p", wctx);
}

JNIEXPORT void JNICALL Java_com_sprich_app_speech_whisper_WhisperJNI_nativeSetThreads(JNIEnv* env, jobject, jlong ctx, jint n) {
    if (n > 0 && n <= 16) g_threads.store(n);
    LOGI("nativeSetThreads %d", g_threads.load());
}

JNIEXPORT jstring JNICALL Java_com_sprich_app_speech_whisper_WhisperJNI_nativeTranscribe(JNIEnv* env, jobject, jlong ctx, jshortArray pcmArray, jstring jlang) {
    if (ctx == 0 || pcmArray == nullptr) return env->NewStringUTF("");
    auto* wctx = reinterpret_cast<struct whisper_context*>(ctx);
    jsize n_samples = env->GetArrayLength(pcmArray);
    if (n_samples == 0) return env->NewStringUTF("");

    // Get PCM as jshort, convert to float [-1,1]
    jshort* data = env->GetShortArrayElements(pcmArray, nullptr);
    if (!data) return env->NewStringUTF("");

    // Silence gate duplicate (Kotlin already does, but keep for safety)
    double sum = 0;
    for (int i = 0; i < n_samples; ++i) {
        double f = data[i] / 32768.0;
        sum += f * f;
    }
    double rms = n_samples > 0 ? sqrt(sum / n_samples) : 0;
    if (rms < 0.018) {
        env->ReleaseShortArrayElements(pcmArray, data, JNI_ABORT);
        return env->NewStringUTF("");
    }

    std::vector<float> pcm_f32(n_samples);
    for (int i = 0; i < n_samples; ++i) pcm_f32[i] = data[i] / 32768.0f;
    env->ReleaseShortArrayElements(pcmArray, data, JNI_ABORT);

    const char* lang_cstr = nullptr;
    std::string lang_str;
    if (jlang != nullptr) {
        const char* tmp = env->GetStringUTFChars(jlang, nullptr);
        if (tmp) {
            lang_str = tmp;
            env->ReleaseStringUTFChars(jlang, tmp);
            if (lang_str != "auto" && !lang_str.empty()) lang_cstr = lang_str.c_str();
        }
    }

    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.n_threads = g_threads.load();
    wparams.n_max_text_ctx = 64;
    wparams.translate = false;
    wparams.no_context = true;
    wparams.single_segment = false;
    wparams.print_special = false;
    wparams.print_progress = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.suppress_blank = true;
    wparams.suppress_nst = true;
    wparams.language = lang_cstr;
    wparams.detect_language = (lang_cstr == nullptr);
    wparams.temperature = 0.0f;
    wparams.no_speech_thold = 0.6f;

    int ret = whisper_full(wctx, wparams, pcm_f32.data(), n_samples);
    if (ret != 0) {
        LOGE("whisper_full failed %d", ret);
        return env->NewStringUTF("");
    }

    int n_seg = whisper_full_n_segments(wctx);
    std::string out;
    for (int i = 0; i < n_seg; ++i) {
        const char* txt = whisper_full_get_segment_text(wctx, i);
        if (!txt) continue;
        // Trim leading space whisper adds
        std::string s = txt;
        // whisper text often has leading space; keep but join with space handling
        if (i > 0 && !out.empty() && !s.empty() && s[0] != ' ') out += " ";
        // Also trim s leading/trailing for cleaner
        // Remove leading spaces for first segment? keep as is for now
        // Simple: if s starts with space and out ends without space, keep single space
        // We'll just append and later trim overall
        out += s;
    }
    // Trim overall
    size_t start = out.find_first_not_of(" \t\n\r");
    size_t end = out.find_last_not_of(" \t\n\r");
    if (start == std::string::npos) out = "";
    else out = out.substr(start, end - start + 1);

    return env->NewStringUTF(out.c_str());
}

} // extern C
