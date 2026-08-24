#include <jni.h>
#include <string>
#include <cmath>
// Dummy JNI that pretends whisper is working and returns incremental mock transcription
// based on PCM length, so the pipeline demonstrates real asset bundling + native load.
// Replace with real whisper.cpp build (whisper_context, whisper_full) for production inference.
extern "C" {
JNIEXPORT jlong JNICALL Java_com_sprich_app_speech_whisper_WhisperJNI_nativeInit(JNIEnv* env, jobject, jstring path) {
    // Return non-zero to indicate success; real whisper would mmap model at path
    return 1;
}
JNIEXPORT void JNICALL Java_com_sprich_app_speech_whisper_WhisperJNI_nativeRelease(JNIEnv*, jobject, jlong ctx) {}
JNIEXPORT jstring JNICALL Java_com_sprich_app_speech_whisper_WhisperJNI_nativeTranscribe(JNIEnv* env, jobject, jlong ctx, jshortArray pcm, jstring lang) {
    jsize len = env->GetArrayLength(pcm);
    // Silence gate — compute RMS, return empty on silence to avoid HelloHello spam
    jshort* data = env->GetShortArrayElements(pcm, nullptr);
    double sum = 0;
    for (int i = 0; i < len; ++i) {
        double f = data[i] / 32768.0;
        sum += f * f;
    }
    env->ReleaseShortArrayElements(pcm, data, JNI_ABORT);
    double rms = len > 0 ? sqrt(sum / len) : 0;
    if (rms < 0.018) {
        return env->NewStringUTF("");
    }
    float sec = len / 16000.0f;
    std::string out;
    if (sec < 1.0) out = "Hello";
    else if (sec < 2.0) out = "Hello world";
    else if (sec < 3.0) out = "Hello world this is";
    else if (sec < 4.5) out = "Hello world this is Sprich";
    else out = "Hello world this is Sprich speaking";
    return env->NewStringUTF(out.c_str());
}
JNIEXPORT void JNICALL Java_com_sprich_app_speech_whisper_WhisperJNI_nativeSetThreads(JNIEnv*, jobject, jlong ctx, jint n) {}
}
