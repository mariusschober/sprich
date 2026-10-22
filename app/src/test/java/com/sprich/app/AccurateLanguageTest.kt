package com.sprich.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.storage.Preferences
import com.sprich.app.ui.settings.accurateLanguageFor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AccurateLanguageTest {
    @Test fun lastExplicitLanguageSurvivesAutomaticMode() = runBlocking {
        val prefs = Preferences(ApplicationProvider.getApplicationContext<Context>())
        prefs.clearAll()
        prefs.setSpeechLanguage(SpeechLanguage.Fixed("de"))
        prefs.setSpeechLanguage(SpeechLanguage.Auto)
        assertEquals("de", prefs.lastAccurateLanguageTag())
        assertEquals("de", accurateLanguageFor(prefs.lastAccurateLanguageTag(), "en-US").tag)
    }

    @Test fun firstAccurateSwitchUsesTheVisibleLocaleInsteadOfAlwaysEnglish() {
        assertEquals("de", accurateLanguageFor(null, "de-DE").tag)
        assertEquals("fr", accurateLanguageFor(null, "fr-CA").tag)
        assertEquals("es", accurateLanguageFor(null, "es-ES").tag)
        assertEquals("en", accurateLanguageFor(null, "en-GB").tag)
        assertEquals("en", accurateLanguageFor(null, "ja-JP").tag)
    }
}
