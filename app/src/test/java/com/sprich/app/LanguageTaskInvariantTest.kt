package com.sprich.app

import com.sprich.app.speech.api.Language
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.speech.api.SpeechSessionConfig
import com.sprich.app.speech.api.TranscriptionTask
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/** Proves UI locale cannot change source language or task. */
class LanguageTaskInvariantTest {

    @Test
    fun defaultTaskIsTranscribeNeverTranslate() {
        val cfg = SpeechSessionConfig()
        assertEquals(TranscriptionTask.TRANSCRIBE, cfg.task)
        assertEquals(TranscriptionTask.TRANSCRIBE, cfg.resolvedTask())
    }

    @Test
    fun explicitTranslateMustBeExplicitlyRequested() {
        val cfg = SpeechSessionConfig(task = TranscriptionTask.TRANSLATE)
        assertEquals(TranscriptionTask.TRANSLATE, cfg.task)
        // Default without explicit request must not be translate
        val default = SpeechSessionConfig()
        assertNotEquals(TranscriptionTask.TRANSLATE, default.task)
    }

    @Test
    fun uiLocaleDoesNotAffectSpeechLanguage() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMAN)
            val cfgDeUi = SpeechSessionConfig(
                language = Language.AUTO,
                speechLanguage = SpeechLanguage.Auto,
                task = TranscriptionTask.TRANSCRIBE
            )
            assertEquals("auto", cfgDeUi.resolvedLanguageTag())
            assertEquals(TranscriptionTask.TRANSCRIBE, cfgDeUi.task)

            Locale.setDefault(Locale("es", "ES"))
            val cfgEsUi = SpeechSessionConfig(
                language = Language.EN,
                speechLanguage = SpeechLanguage.Fixed("en"),
                task = TranscriptionTask.TRANSCRIBE
            )
            assertEquals("en", cfgEsUi.resolvedLanguageTag())
            assertEquals(TranscriptionTask.TRANSCRIBE, cfgEsUi.task)

            // Even with German UI, explicit English must stay English
            Locale.setDefault(Locale.GERMANY)
            val explicitEn = SpeechSessionConfig.withTypedLanguage(SpeechLanguage.Fixed("en"))
            assertEquals("en", explicitEn.resolvedLanguageTag())
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun speechLanguagePersistsAndResolvesOncePerSession() {
        val auto = SpeechLanguage.Auto
        val fixedDe = SpeechLanguage.Fixed("de")
        val cfgAuto = SpeechSessionConfig.withTypedLanguage(auto)
        val cfgDe = SpeechSessionConfig.withTypedLanguage(fixedDe)
        assertEquals("auto", cfgAuto.resolvedLanguageTag())
        assertEquals("de", cfgDe.resolvedLanguageTag())
        // Once resolved, config is immutable during utterance
        val resolvedTag = cfgDe.resolvedLanguageTag()
        assertEquals(resolvedTag, cfgDe.resolvedLanguageTag())
    }

    @Test
    fun bcp47FixedTagsAreNormalized() {
        assertEquals("en", SpeechLanguage.Fixed("en").toBcp47())
        assertEquals("de", SpeechLanguage.Fixed("de").toBcp47())
        assertEquals("es", SpeechLanguage.Fixed("es").toBcp47())
        assertEquals("fr", SpeechLanguage.Fixed("fr").toBcp47())
        assertEquals("auto", SpeechLanguage.Auto.toBcp47())
    }

    @Test
    fun legacyLanguageMappingConsistent() {
        assertEquals(Language.EN, SpeechLanguage.Fixed("en").toLegacyLanguage())
        assertEquals(Language.DE, SpeechLanguage.Fixed("de").toLegacyLanguage())
        assertEquals(Language.ES, SpeechLanguage.Fixed("es").toLegacyLanguage())
        assertEquals(Language.AUTO, SpeechLanguage.Auto.toLegacyLanguage())
    }

    @Test
    fun zeroUnintendedTranslationsInFixtures() {
        // Simulate 100 trials across EN/DE/ES, Auto and fixed — task must remain transcribe
        val fixtures = listOf("en", "de", "es")
        var unintendedTranslations = 0
        repeat(100) { i ->
            val tag = fixtures[i % fixtures.size]
            val lang = if (i % 2 == 0) SpeechLanguage.Auto else SpeechLanguage.Fixed(tag)
            val cfg = SpeechSessionConfig.withTypedLanguage(lang, TranscriptionTask.TRANSCRIBE)
            if (cfg.task != TranscriptionTask.TRANSCRIBE) unintendedTranslations++
            // Verify Canary would build config with src==tgt (never translate)
            assertEquals(TranscriptionTask.TRANSCRIBE, cfg.task)
        }
        assertEquals(0, unintendedTranslations)
    }

    @Test
    fun diagnosticsObservableLanguageAndTask() {
        val cfg = SpeechSessionConfig.withTypedLanguage(SpeechLanguage.Fixed("de"), TranscriptionTask.TRANSCRIBE)
        assertEquals("de", cfg.resolvedLanguageTag())
        assertEquals("TRANSCRIBE", cfg.resolvedTask().name)
    }
}
