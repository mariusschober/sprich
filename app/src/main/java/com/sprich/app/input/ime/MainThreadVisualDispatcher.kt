package com.sprich.app.input.ime

import android.os.Handler
import android.os.Looper

/** Marshals expensive view mutations from the audio reader onto the main thread. */
internal object MainThreadVisualDispatcher {
    fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else Handler(Looper.getMainLooper()).post(block)
    }
}
