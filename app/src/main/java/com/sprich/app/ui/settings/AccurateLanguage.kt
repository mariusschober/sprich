package com.sprich.app.ui.settings

import com.sprich.app.speech.api.SpeechLanguage

/**
 * Entering Accurate keeps the last explicit language; a first-ever switch uses the app/system
 * locale as a visible starting point instead of silently forcing English.
 */
internal fun accurateLanguageFor(last: String?, localeTag: String): SpeechLanguage.Fixed {
    val supported = setOf("en", "de", "es", "fr")
    val remembered = last?.lowercase()?.substringBefore('-')?.takeIf { it in supported }
    if (remembered != null) return SpeechLanguage.Fixed(remembered)
    val fromLocale = localeTag.lowercase().substringBefore('-').takeIf { it in supported } ?: "en"
    return SpeechLanguage.Fixed(fromLocale)
}
