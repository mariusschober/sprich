package com.sprich.app.input.ime

import com.sprich.app.speech.api.SpeechLanguage

/** Compact voice-bar language control; changes apply to the next phrase. */
internal fun nextSpeechLanguage(current: SpeechLanguage): SpeechLanguage = when {
    current is SpeechLanguage.Auto -> SpeechLanguage.Fixed("en")
    (current as? SpeechLanguage.Fixed)?.tag == "en" -> SpeechLanguage.Fixed("de")
    (current as? SpeechLanguage.Fixed)?.tag == "de" -> SpeechLanguage.Fixed("es")
    (current as? SpeechLanguage.Fixed)?.tag == "es" -> SpeechLanguage.Fixed("fr")
    else -> SpeechLanguage.Auto
}

internal fun languageChipLabel(language: SpeechLanguage): String = when (language) {
    is SpeechLanguage.Auto -> "AUTO"
    is SpeechLanguage.Fixed -> language.tag.substringBefore('-').uppercase()
}
