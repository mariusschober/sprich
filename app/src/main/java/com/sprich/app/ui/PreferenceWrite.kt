package com.sprich.app.ui

import kotlinx.coroutines.CancellationException

/**
 * A failed DataStore write must not crash the activity or leave a switch lying about what
 * was persisted. Returns true only when the write completed.
 */
internal suspend fun persistPreference(onFailure: () -> Unit, block: suspend () -> Unit): Boolean =
    try {
        block()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        onFailure()
        false
    }
