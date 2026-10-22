package com.sprich.app.input.ime

import com.sprich.app.speech.api.SpeechLanguage

/** Compact voice-bar language control; changes apply to the next phrase. */
internal fun nextSpeechLanguage(current: SpeechLanguage): SpeechLanguage = when ((current as? SpeechLanguage.Fixed)?.tag?.substringBefore('-')) {
    "en" -> SpeechLanguage.Fixed("de")
    "de" -> SpeechLanguage.Fixed("es")
    "es" -> SpeechLanguage.Fixed("fr")
    "fr" -> SpeechLanguage.Auto
    else -> if (current is SpeechLanguage.Auto) SpeechLanguage.Fixed("en") else SpeechLanguage.Auto
}

internal fun languageChipLabel(language: SpeechLanguage): String = when (language) {
    is SpeechLanguage.Auto -> "AUTO"
    is SpeechLanguage.Fixed -> language.tag.substringBefore('-').uppercase()
}
