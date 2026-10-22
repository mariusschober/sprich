package com.sprich.app

import com.sprich.app.input.ime.languageChipLabel
import com.sprich.app.input.ime.nextSpeechLanguage
import com.sprich.app.speech.api.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageChipTest {
    @Test fun oneTapCyclesAutoAndTheFourSupportedLanguages() {
        var language: SpeechLanguage = SpeechLanguage.Auto
        val seen = mutableListOf(languageChipLabel(language))
        repeat(5) {
            language = nextSpeechLanguage(language)
            seen += languageChipLabel(language)
        }
        assertEquals(listOf("AUTO", "EN", "DE", "ES", "FR", "AUTO"), seen)
    }

    @Test fun regionalTagsKeepTheBaseLanguageLabel() {
        assertEquals("DE", languageChipLabel(SpeechLanguage.Fixed("de-DE")))
        assertEquals(SpeechLanguage.Fixed("es"), nextSpeechLanguage(SpeechLanguage.Fixed("de-DE")))
    }
}
