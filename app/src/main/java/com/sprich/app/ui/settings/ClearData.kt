package com.sprich.app.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Clearing app data can take seconds on a large model install; never run it on Main. */
internal suspend fun runClearData(clear: () -> Boolean): Boolean = withContext(Dispatchers.IO) {
    try {
        clear()
    } catch (_: Exception) {
        false
    }
}
