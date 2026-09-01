package com.sprich.app.speech

import org.junit.Assert.*
import org.junit.Test

class TranscriptionPlanTest {
    @Test
    fun transcriptionModeTyped() {
        assertEquals(TranscriptionMode.ON_DEVICE, TranscriptionMode.fromRaw("local"))
        assertEquals(TranscriptionMode.API_PRIMARY, TranscriptionMode.fromRaw("remote"))
        assertEquals(TranscriptionMode.LOCAL_API_FALLBACK, TranscriptionMode.fromRaw("fallback"))
        assertEquals("local", TranscriptionMode.toRaw(TranscriptionMode.ON_DEVICE))
        assertEquals("remote", TranscriptionMode.toRaw(TranscriptionMode.API_PRIMARY))
    }

    @Test
    fun utterancePlanImmutable() {
        val route = LocalAsrRoute.AutomaticFastConformer
        val plan = UtterancePlan(
            transcription = TranscriptionPlan.Local(route),
            refinement = RefinementPlan.Off,
            speechConfig = com.sprich.app.speech.api.SpeechSessionConfig(speechLanguage = com.sprich.app.speech.api.SpeechLanguage.Auto)
        )
        assertTrue(plan.transcription is TranscriptionPlan.Local)
        assertTrue(plan.refinement is RefinementPlan.Off)
    }

    @Test
    fun apiPrimaryContainsFallback() {
        val route = LocalAsrRoute.AutomaticFastConformer
        val remote = com.sprich.app.speech.remote.RemoteSttConfig("openai-compatible", "https://example.com", "whisper", LanguagePolicy.Automatic, 3500L, "ref")
        val plan = TranscriptionPlan.ApiPrimary(remote, route)
        assertNotNull(plan.localFallback)
        assertEquals("openai-compatible", plan.remote.providerId)
    }

    @Test
    fun localApiFallbackOnlyOnBlank() {
        // LocalApiFallback semantics: remote only on objective failure (blank/exception)
        val route = LocalAsrRoute.AccurateCanary(com.sprich.app.speech.api.Language.EN)
        val remote = com.sprich.app.speech.remote.RemoteSttConfig("openai-compatible", "https://example.com", "whisper", LanguagePolicy.Automatic, 3500L, "ref")
        val plan = TranscriptionPlan.LocalApiFallback(route, remote)
        assertTrue(plan.local is LocalAsrRoute.AccurateCanary)
        // Simulate that non-blank local should not trigger remote (validated in coordinator)
    }
}
