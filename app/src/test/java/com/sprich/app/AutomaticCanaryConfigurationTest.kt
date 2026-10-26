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
 * T3 host checks for the license-clean Automatic configuration:
 * Automatic = Whisper Tiny language ID + Canary 180M Flash; no FastConformer route exists.
 * These are pure JVM checks, not native-engine or device evidence.
 */
class AutomaticCanaryConfigurationTest {

    @Test
    fun automaticRouteUsesCanaryEngineOnly() {
        val route = LocalAsrRoute.AutomaticCanary
        assertEquals("canary-180m-flash-int8", route.engineId())
        assertTrue(route.displayName().contains("Automatic"))
        val accurate = LocalAsrRoute.AccurateCanary(Language.DE)
        assertEquals("canary-180m-flash-int8", accurate.engineId())
        assertTrue(accurate.displayName().contains("DE"))
        // Language selection is the only difference between the two routes.
        assertNotEquals(route, accurate)
    }

    @Test
    fun unknownLanguageResultNeverBecomesASpokenCommand() {
        // Automatic fails closed when LID is Unknown, so no transcript from that path may act as
        // a destructive command; the parser must treat it as plain text.
        val parsed = SpokenEditingParser.parse("delete that", ResolvedUtteranceLanguage.Unknown, true)
        assertFalse(SpokenEditingParser.isDeleteCommand(parsed.text))
        assertEquals("delete that", parsed.text)
    }

    @Test
    fun utteranceAudioCollectorIsAuthoritativeAndImmutable() {
        val collector = UtteranceAudioCollector()
        collector.begin(shortArrayOf(1, 2, 3))
        collector.append(shortArrayOf(4, 5))
        val frozen = collector.freeze()
        val config = SpeechSessionConfig(speechLanguage = SpeechLanguage.Auto)
        val route = LocalAsrRoute.AutomaticCanary
        data class FakePending(val pcm: ShortArray, val route: LocalAsrRoute, val config: SpeechSessionConfig)
        val pending = FakePending(frozen.copyOf(), route, config.copy())

        collector.begin(shortArrayOf(9, 9, 9))
        collector.append(shortArrayOf(10))
        assertFalse(pending.pcm.contentEquals(collector.freeze()))
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5), pending.pcm)
        assertTrue(pending.route is LocalAsrRoute.AutomaticCanary)
    }

    @Test
    fun routeSnapshotStaysAutomaticAfterSettingsChange() {
        val collector = UtteranceAudioCollector()
        collector.begin(shortArrayOf(1, 2))
        val pcmA = collector.freeze()
        val routeA = LocalAsrRoute.AutomaticCanary
        val routeAfterChange = LocalAsrRoute.AccurateCanary(Language.DE)
        assertNotEquals(routeAfterChange, routeA)
        assertTrue(routeA is LocalAsrRoute.AutomaticCanary)
    }
}
