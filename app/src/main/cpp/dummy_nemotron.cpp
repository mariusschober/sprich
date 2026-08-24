#include <jni.h>
// Nemotron dummy
extern "C" {
JNIEXPORT jlong JNICALL Java_com_sprich_app_speech_nemotron_NemotronJNI_nativeInit(JNIEnv* env, jobject, jstring path) { return 0; }
JNIEXPORT void JNICALL Java_com_sprich_app_speech_nemotron_NemotronJNI_nativeRelease(JNIEnv*, jobject, jlong ctx) {}
}
