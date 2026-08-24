package com.sprich.app.speech.whisper

import android.util.Log

/**
 * JNI bridge to whisper.cpp. Gracefully degrades to mock if native lib missing.
 * Native lib name: sprich_whisper (libsprich_whisper.so)
 *
 * Build: app/src/main/cpp/CMakeLists.txt compiles whisper.cpp into that lib.
 * Until built, isAvailable() returns false and engine falls back to mock transcription.
 */
object WhisperJNI {
    private var loaded = false
    private var tried = false

    fun isAvailable(): Boolean {
        if (tried) return loaded
        tried = true
        loaded = try {
            System.loadLibrary("sprich_whisper")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w("WhisperJNI", "native lib not present, using mock")
            false
        } catch (e: Exception) {
            Log.w("WhisperJNI", "load failed", e)
            false
        }
        return loaded
    }

    // Native methods — signatures must match cpp
    external fun nativeInit(modelPath: String): Long // returns context pointer
    external fun nativeRelease(ctx: Long)
    external fun nativeTranscribe(ctx: Long, pcm: ShortArray, lang: String): String
    external fun nativeSetThreads(ctx: Long, n: Int)
}
