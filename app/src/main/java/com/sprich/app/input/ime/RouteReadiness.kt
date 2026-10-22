package com.sprich.app.input.ime

import com.sprich.app.speech.LocalAsrRoute
import com.sprich.app.speech.TranscriptionMode

/**
 * The audio thread must never stat model files. Readiness is sampled once when a session
 * starts; an onset plan can only use that immutable in-memory answer.
 */
internal fun fallbackRouteFor(
    mode: TranscriptionMode,
    apiLocalFallback: Boolean,
    cachedReady: Boolean,
    route: LocalAsrRoute,
): LocalAsrRoute? = route.takeIf { mode == TranscriptionMode.API_PRIMARY && apiLocalFallback && cachedReady }
