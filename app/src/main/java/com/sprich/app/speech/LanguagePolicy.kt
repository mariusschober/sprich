package com.sprich.app.speech

/**
 * Provider-neutral language policy.
 * - Automatic: provider does native automatic detection (preferred, no local LID needed)
 * - Fixed: explicit BCP-47 tag
 */
sealed interface LanguagePolicy {
    data object Automatic : LanguagePolicy
    data class Fixed(val tag: String) : LanguagePolicy {
        init { require(tag.isNotBlank()) }
    }

    fun toHint(): String? = when (this) {
        is Automatic -> null
        is Fixed -> tag
    }

    companion object {
        fun fromSpeechLanguage(lang: com.sprich.app.speech.api.SpeechLanguage): LanguagePolicy =
            when (lang) {
                is com.sprich.app.speech.api.SpeechLanguage.Auto -> Automatic
                is com.sprich.app.speech.api.SpeechLanguage.Fixed -> Fixed(lang.tag)
            }
    }
}
