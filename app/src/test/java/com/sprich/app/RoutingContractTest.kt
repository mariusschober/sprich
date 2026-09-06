package com.sprich.app

import com.sprich.app.speech.LanguagePolicy
import com.sprich.app.speech.LocalAsrRoute
import com.sprich.app.speech.ResolvedUtteranceLanguage
import com.sprich.app.speech.api.Language
import com.sprich.app.speech.api.SpeechLanguage
import org.junit.Assert.*
import org.junit.Test

/**
 * P06 (§7) routing contract, host-runnable slice: language selection is
 * decoupled from engine selection, an explicit language never silently
 * means "load Canary", and per-utterance routing carries no cross-utterance
 * state. Model quality/latency comparisons, early-LID timing, and the
 * streaming experiment need devices + corpus and stay BLOCKED.
 */
class RoutingContractTest {

    @Test
    fun automaticMeansFastConformerNeverCanary() {
        val route = LocalAsrRoute.fromLanguage(SpeechLanguage.Auto)
        assertTrue(route is LocalAsrRoute.AutomaticFastConformer)
        assertEquals("fastconformer-ctc-en-de-es-fr-14288-int8", route.engineId())
    }

    @Test
    fun explicitLanguageMeansAccurateCanaryInThatLanguage() {
        for (lang in listOf(Language.EN, Language.DE, Language.ES, Language.FR)) {
            val route = LocalAsrRoute.fromLanguage(SpeechLanguage.Fixed(lang.code))
            assertTrue(route is LocalAsrRoute.AccurateCanary)
            assertEquals(lang, (route as LocalAsrRoute.AccurateCanary).language)
            assertEquals("canary-180m-flash-int8", route.engineId())
        }
    }

    @Test
    fun routingIsStatelessAcrossUtterances() {
        // No previous-utterance language leaks: identical inputs always agree,
        // and Automatic never resolves from a prior explicit choice.
        val de = LocalAsrRoute.fromLanguage(SpeechLanguage.Fixed("de"))
        val auto1 = LocalAsrRoute.fromLanguage(SpeechLanguage.Auto)
        val auto2 = LocalAsrRoute.fromLanguage(SpeechLanguage.Auto)
        assertEquals(auto1, auto2)
        assertTrue(auto1 is LocalAsrRoute.AutomaticFastConformer)
        assertTrue(de is LocalAsrRoute.AccurateCanary)
    }

    @Test
    fun languagePolicyHints() {
        assertNull(LanguagePolicy.Automatic.toHint())
        assertEquals("de-DE", LanguagePolicy.Fixed("de-DE").toHint())
        assertEquals(LanguagePolicy.Automatic, LanguagePolicy.fromSpeechLanguage(SpeechLanguage.Auto))
        assertEquals(LanguagePolicy.Fixed("es"), LanguagePolicy.fromSpeechLanguage(SpeechLanguage.Fixed("es")))
    }

    @Test
    fun unknownLanguageIsDistinctFromEveryKnownLanguage() {
        // The command policy relies on this: Unknown never authorizes commands.
        for (lang in listOf(Language.EN, Language.DE, Language.ES, Language.FR)) {
            assertTrue(ResolvedUtteranceLanguage.Unknown != ResolvedUtteranceLanguage.Known(lang))
        }
    }
}
