package com.sprich.app.speech

import com.sprich.app.speech.api.Language

/**
 * Explicit transcription routing for local ASR.
 *
 * Automatic = Tiny LID + FastConformer (primary, no Canary)
 * Accurate  = Canary with explicit language
 *
 * Route is captured per utterance (PendingUtterance.route) so a Settings change cannot
 * cause a queued Automatic utterance to be decoded by Canary German.
 *
 * Future API route will be added alongside, not by overloading Language.AUTO.
 */
sealed interface LocalAsrRoute {
    data object AutomaticFastConformer : LocalAsrRoute
    data class AccurateCanary(val language: Language) : LocalAsrRoute

    fun displayName(): String = when (this) {
        is AutomaticFastConformer -> "Automatic · Fast on-device"
        is AccurateCanary -> "Accurate · ${language.code.uppercase()}"
    }
    fun engineId(): String = when (this) {
        is AutomaticFastConformer -> "fastconformer-ctc-en-de-es-fr-14288-int8"
        is AccurateCanary -> "canary-180m-flash-int8"
    }
}
