package com.sprich.app.speech

import com.sprich.app.speech.api.Language

/**
 * Explicit transcription routing for local ASR.
 *
 * Automatic = Whisper Tiny language ID + Canary 180M Flash (license-clean default)
 * Accurate  = Canary with explicit language
 *
 * Route is captured per utterance (PendingUtterance.route) so a Settings change cannot
 * cause a queued Automatic utterance to be decoded with a different engine/language.
 *
 * Future API route will be added alongside, not by overloading Language.AUTO.
 */
sealed interface LocalAsrRoute {
    data object AutomaticCanary : LocalAsrRoute
    data class AccurateCanary(val language: Language) : LocalAsrRoute

    fun displayName(): String = when (this) {
        is AutomaticCanary -> "Automatic · Canary on-device"
        is AccurateCanary -> "Accurate · ${language.code.uppercase()}"
    }
    fun engineId(): String = when (this) {
        is AutomaticCanary -> "canary-180m-flash-int8"
        is AccurateCanary -> "canary-180m-flash-int8"
    }

    companion object {
        fun fromLanguage(language: com.sprich.app.speech.api.SpeechLanguage): LocalAsrRoute {
            val selected = language.toLegacyLanguage()
            return if (selected == Language.AUTO) AutomaticCanary else AccurateCanary(selected)
        }
    }
}
