package com.sprich.app.speech.canary

import com.sprich.app.speech.api.Language
import org.junit.Assert.assertEquals
import org.junit.Test

class CanaryLanguageTaskTest {

    @Test
    fun everySupportedLanguageTranscribesWithoutTranslation() {
        listOf(Language.EN, Language.DE, Language.ES, Language.FR).forEach { language ->
            val task = canaryLanguageTask(language)
            assertEquals(language.code, task.sourceLanguage)
            assertEquals(task.sourceLanguage, task.targetLanguage)
        }
    }

    @Test
    fun legacyAutoNeverSelectsAGuessedTranslationBranch() {
        val task = canaryLanguageTask(Language.AUTO)
        assertEquals(Language.EN.code, task.sourceLanguage)
        assertEquals(task.sourceLanguage, task.targetLanguage)
    }
}
