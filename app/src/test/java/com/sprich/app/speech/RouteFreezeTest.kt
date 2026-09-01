package com.sprich.app.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sprich.app.core.audio.UtteranceAudioCollector
import com.sprich.app.input.lifecycle.UtteranceToken
import com.sprich.app.speech.api.SpeechLanguage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RouteFreezeTest {

    @Test
    fun routeFrozenAtOnsetDespiteSettingsChange() {
        // Simulate SprichIME behavior: capture route at onset, chunks continue, endpoint still uses frozen route
        var speechLang: SpeechLanguage = SpeechLanguage.Auto
        fun determineRoute(lang: SpeechLanguage): LocalAsrRoute = when (lang) {
            is SpeechLanguage.Auto -> LocalAsrRoute.AutomaticFastConformer
            is SpeechLanguage.Fixed -> LocalAsrRoute.AccurateCanary(lang.toLegacyLanguage())
        }
        // Onset
        val routeAtOnset = determineRoute(speechLang)
        assertTrue(routeAtOnset is LocalAsrRoute.AutomaticFastConformer)
        // Simulate Settings change to DE Accurate mid-utterance
        speechLang = SpeechLanguage.Fixed("de")
        val routeMidChunk = routeAtOnset // should remain frozen, not re-read
        assertTrue(routeMidChunk is LocalAsrRoute.AutomaticFastConformer)
        val routeAtEndpoint = routeAtOnset // endpoint must still be Automatic
        assertTrue(routeAtEndpoint is LocalAsrRoute.AutomaticFastConformer)
        // Next utterance should use new DE Accurate
        val routeNextOnset = determineRoute(speechLang)
        assertTrue(routeNextOnset is LocalAsrRoute.AccurateCanary)
        assertEquals("de", (routeNextOnset as LocalAsrRoute.AccurateCanary).language.code)
    }

    @Test
    fun collectorStrictBoundProperty() {
        val collector = UtteranceAudioCollector(maxSamples = 100)
        collector.begin(ShortArray(50){1})
        collector.append(ShortArray(60){2}) // would overflow to 110 -> should drop oldest 10 -> size 100
        assertEquals(100, collector.size())
        collector.append(ShortArray(200){3}) // oversized -> keep last 100 (all 3s)
        assertEquals(100, collector.size())
        assertTrue(collector.snapshot().all { it == 3.toShort() })
    }
}
