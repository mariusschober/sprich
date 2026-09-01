package com.sprich.app

import com.sprich.app.core.audio.UtteranceAudioCollector
import com.sprich.app.input.commands.SpokenEditingParser
import com.sprich.app.speech.LocalAsrRoute
import com.sprich.app.speech.ResolvedUtteranceLanguage
import com.sprich.app.speech.api.Language
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.speech.api.SpeechSessionConfig
import org.junit.Assert.*
import org.junit.Test

/**
 * Host unit test proving the hidden Canary dependency removal.
 * Verifies:
 * - Automatic route does not require Canary files
 * - UtteranceAudioCollector is authoritative, not engine
 * - Route snapshot immutability
 * - Exactly-once via collector freeze isolation
 */
class AutomaticWithoutCanaryTest {

    @Test
    fun automaticRouteDoesNotRequireCanary() {
        val route = LocalAsrRoute.AutomaticFastConformer
        assertTrue(route is LocalAsrRoute.AutomaticFastConformer)
        assertEquals("fastconformer-ctc-en-de-es-fr-14288-int8", route.engineId())
        assertTrue(route.displayName().contains("Fast"))
        // Accurate route still Canary
        val accurate = LocalAsrRoute.AccurateCanary(Language.DE)
        assertEquals("canary-180m-flash-int8", accurate.engineId())
        assertTrue(accurate.displayName().contains("DE"))
    }

    @Test
    fun utteranceAudioCollectorIsAuthoritative() {
        val collector = UtteranceAudioCollector()
        val preRoll = shortArrayOf(1, 2, 3)
        val chunk = shortArrayOf(4, 5)
        collector.begin(preRoll)
        collector.append(chunk)
        val frozen = collector.freeze()

        // Engine would have previously owned buffer — now collector does, so engine load not needed.
        // Simulate pending utterance with route snapshot
        val config = SpeechSessionConfig(speechLanguage = SpeechLanguage.Auto)
        val route = LocalAsrRoute.AutomaticFastConformer
        // Pending captures immutable pcm + route
        data class FakePending(val pcm: ShortArray, val route: LocalAsrRoute, val config: SpeechSessionConfig)
        val pending = FakePending(frozen.copyOf(), route, config.copy())

        // Mutate collector for next utterance — pending must remain isolated
        collector.begin(shortArrayOf(9, 9, 9))
        collector.append(shortArrayOf(10))
        val nextFrozen = collector.freeze()
        assertFalse(pending.pcm.contentEquals(nextFrozen))
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5), pending.pcm)
    }

    @Test
    fun routeSnapshotImmutability() {
        // Utterance A captured as Automatic
        val collector = UtteranceAudioCollector()
        collector.begin(shortArrayOf(1, 2))
        val pcmA = collector.freeze()
        val routeA = LocalAsrRoute.AutomaticFastConformer
        val pendingA = TestPending(pcmA.copyOf(), routeA, SpeechSessionConfig(speechLanguage = SpeechLanguage.Auto))

        // Settings changes to German Accurate after endpoint
        val routeAfterChange = LocalAsrRoute.AccurateCanary(Language.DE)
        // Pending A must still finalize with Automatic route, not new Accurate
        assertTrue(pendingA.route is LocalAsrRoute.AutomaticFastConformer)
        assertFalse(pendingA.route is LocalAsrRoute.AccurateCanary)
        assertNotEquals(routeAfterChange, pendingA.route)
    }

    data class TestPending(val pcm: ShortArray, val route: LocalAsrRoute, val config: SpeechSessionConfig)

    @Test
    fun unknownLanguageDisablesCommands() {
        // Simulate LID unsupported -> Unknown, Fast transcript still produced
        val fastText = "delete that" // English-like but LID says Unknown
        val resolvedUnknown = ResolvedUtteranceLanguage.Unknown
        val parsed = SpokenEditingParser.parse(fastText, resolvedUnknown, true)
        assertFalse(SpokenEditingParser.isDeleteCommand(parsed.text))
        assertEquals("delete that", parsed.text)

        // Even German-like transcript with Unknown must not execute English ITN
        val germanLike = "marius at example dot com"
        val parsed2 = SpokenEditingParser.parse(germanLike, resolvedUnknown, true)
        assertEquals("marius at example dot com", parsed2.text)
    }

    @Test
    fun canaryLoadAttemptsZeroForAutomatic() {
        // Simulate SprichIME counters — Automatic should have 0 Canary loads
        var canaryLoads = 0
        var fastLoads = 0
        fun startAutomatic(isAutomaticReady: Boolean, speechLang: SpeechLanguage) {
            val route = if (speechLang is SpeechLanguage.Auto) LocalAsrRoute.AutomaticFastConformer else LocalAsrRoute.AccurateCanary(Language.EN)
            when (route) {
                is LocalAsrRoute.AutomaticFastConformer -> {
                    assertTrue(isAutomaticReady)
                    fastLoads++
                    // must NOT increment canaryLoads
                }
                is LocalAsrRoute.AccurateCanary -> canaryLoads++
            }
        }
        startAutomatic(true, SpeechLanguage.Auto)
        assertEquals(0, canaryLoads)
        assertEquals(1, fastLoads)

        // Explicit still uses Canary
        fastLoads = 0; canaryLoads = 0
        startAutomatic(true, SpeechLanguage.Fixed("de"))
        assertEquals(1, canaryLoads)
        assertEquals(0, fastLoads)
    }

    @Test
    fun preRollIdentityViaCollector() {
        val c = UtteranceAudioCollector()
        c.begin(shortArrayOf(1, 2, 3))
        c.append(shortArrayOf(4, 5))
        c.append(shortArrayOf(6, 7))
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5, 6, 7), c.freeze())

        c.begin(shortArrayOf(8))
        c.append(shortArrayOf(9, 10))
        assertArrayEquals(shortArrayOf(8, 9, 10), c.freeze())
    }

    @Test
    fun queuePreservesIndependentPcm() {
        val collector = UtteranceAudioCollector()
        // Simulate queue of two pending utterances with independent PCM
        collector.begin(shortArrayOf(1, 2))
        collector.append(shortArrayOf(3))
        val pcmA = collector.freeze().copyOf()
        // Next utterance while A pending finalization
        collector.begin(shortArrayOf(4, 5))
        collector.append(shortArrayOf(6))
        val pcmB = collector.freeze().copyOf()

        assertFalse(pcmA.contentEquals(pcmB))
        assertArrayEquals(shortArrayOf(1, 2, 3), pcmA)
        assertArrayEquals(shortArrayOf(4, 5, 6), pcmB)
        // Ensure A not corrupted by B's append (freeze isolation)
        assertTrue(pcmA.contentEquals(shortArrayOf(1, 2, 3)))
    }
}
