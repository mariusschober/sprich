package com.sprich.app

import com.sprich.app.core.audio.CaptureReadiness
import com.sprich.app.core.audio.ContinuousSpeechPolicy
import com.sprich.app.core.audio.StartupAudioBuffer
import com.sprich.app.core.vad.EndpointConfig
import com.sprich.app.core.vad.EndpointSignals
import com.sprich.app.core.vad.SpeechEndpoint
import com.sprich.app.core.vad.VadRoutePolicy
import org.junit.Assert.*
import org.junit.Test

/**
 * Host tests for the P01/P02/P05 policy layer (plan §§5.1–5.3):
 * bounded startup buffer + readiness split, route-independent VAD endpoint
 * policy, and bounded continuous-speech caps with verified reconciliation.
 * Android wiring (AudioCapture feed, Silero detector, endpoint-driven splits)
 * needs Gradle + device and stays BLOCKED.
 */
class P01P02P05PolicyTest {

    // P01: 8 s of 16 kHz mono PCM16 = 128,000 samples = 256,000 bytes.

    @Test
    fun startupBufferCapsAtEightSeconds() {
        assertEquals(128_000, StartupAudioBuffer.STARTUP_BUFFER_MAX_SAMPLES)
        assertEquals(256_000, StartupAudioBuffer.STARTUP_BUFFER_MAX_BYTES)
        val b = StartupAudioBuffer()
        b.begin(1L)
        b.append(ShortArray(200_000) { 7 }, 1L)
        assertEquals(128_000, b.sizeSamples())
        assertEquals(256_000, b.sizeBytes())
    }

    @Test
    fun startupBufferDropsStaleGenerationsAndDrainsOnce() {
        val b = StartupAudioBuffer()
        b.begin(1L)
        b.append(ShortArray(1000) { 3 }, 2L) // wrong generation: ignored
        assertEquals(0, b.sizeSamples())
        b.append(ShortArray(1000) { 3 }, 1L)
        assertEquals(1000, b.drainOnce(1L).size)
        assertEquals(0, b.drainOnce(1L).size) // exactly once
        b.begin(2L)
        b.append(ShortArray(500) { 4 }, 2L)
        b.invalidate() // cancelled load continuation must not feed or drain old capture
        assertFalse(b.isArmed())
        assertEquals(0, b.drainOnce(2L).size)
        b.append(ShortArray(500) { 4 }, 2L)
        assertEquals(0, b.sizeSamples())
    }

    @Test
    fun captureReadinessIsIndependentOfRecognizerReadiness() {
        val r = CaptureReadiness()
        r.onFirstPcm(0L)
        assertTrue(r.captureReady)
        assertFalse(r.recognizerReady) // warming cue, not a recording claim
        r.onRecognizerLoaded(0L)
        assertTrue(r.recognizerReady)
        r.invalidate() // generation bump retires stale continuations
        assertFalse(r.captureReady)
        assertFalse(r.recognizerReady)
        r.onFirstPcm(0L) // stale generation ignored
        assertFalse(r.captureReady)
        r.onFirstPcm(1L)
        assertTrue(r.captureReady)
    }

    // P02: neural presence usable in every route; attempt count never decides.

    @Test
    fun neuralSpeechIsNeverCutByProviderCompletion() {
        val s = EndpointSignals(
            neuralPresence = true, energySpeech = false,
            providerCompleted = true, providerEndpointAuthoritative = true,
        )
        assertEquals(SpeechEndpoint.SPEECH, VadRoutePolicy.evaluate(s, 10_000L, Long.MAX_VALUE))
    }

    @Test
    fun attemptCountDoesNotChangeDecisions() {
        val cases = listOf(
            EndpointSignals(true, false, true, true) to Triple(10_000L, Long.MAX_VALUE, true),
            EndpointSignals(false, false, false, false) to Triple(10_000L, Long.MAX_VALUE, true),
            EndpointSignals(null, true, false, false) to Triple(0L, Long.MAX_VALUE, true),
            EndpointSignals(false, false, true, true) to Triple(10_000L, 100L, true),
        )
        for ((signals, args) in cases) {
            val (silence, sinceEndpoint, ever) = args
            assertEquals(
                VadRoutePolicy.evaluate(signals, silence, sinceEndpoint, localAsrAttemptCount = 0, hasSpeechEver = ever),
                VadRoutePolicy.evaluate(signals, silence, sinceEndpoint, localAsrAttemptCount = 9, hasSpeechEver = ever),
            )
        }
    }

    @Test
    fun endpointRequiresDebounceAfterPreviousEndpoint() {
        val s = EndpointSignals(neuralPresence = false, energySpeech = false)
        assertEquals(SpeechEndpoint.ENDPOINT, VadRoutePolicy.evaluate(s, 10_000L, Long.MAX_VALUE))
        assertEquals(SpeechEndpoint.NO_SPEECH, VadRoutePolicy.evaluate(s, 10_000L, 100L))
    }

    @Test
    fun degradedModeUsesEnergyForOnsetOnly() {
        assertEquals(
            SpeechEndpoint.SPEECH,
            VadRoutePolicy.evaluate(EndpointSignals(null, true), 0L, Long.MAX_VALUE),
        )
        assertEquals(
            SpeechEndpoint.NO_SPEECH,
            VadRoutePolicy.evaluate(EndpointSignals(null, false), 10_000L, Long.MAX_VALUE, hasSpeechEver = false),
        )
    }

    @Test
    fun authoritativelyCompletedProviderMayEndpointDebouncedSilence() {
        val s = EndpointSignals(false, false, providerCompleted = true, providerEndpointAuthoritative = true)
        assertEquals(SpeechEndpoint.ENDPOINT, VadRoutePolicy.evaluate(s, 900L, 5_000L))
        val unauth = EndpointSignals(false, false, providerCompleted = true, providerEndpointAuthoritative = false)
        assertEquals(SpeechEndpoint.NO_SPEECH, VadRoutePolicy.evaluate(unauth, 100L, 5_000L))
    }

    // P05: caps are ceilings; overlap reconciles only on verified exact match.

    @Test
    fun noSplitUnderSoftCap() {
        assertNull(ContinuousSpeechPolicy.planSegmentSplit(ContinuousSpeechPolicy.SEGMENT_SOFT_CAP_SAMPLES - 1, null))
        assertNull(ContinuousSpeechPolicy.planSegmentSplit(0, 100))
    }

    @Test
    fun safePauseInsideWindowWinsOtherwiseHardCap() {
        val soft = ContinuousSpeechPolicy.SEGMENT_SOFT_CAP_SAMPLES
        val hard = ContinuousSpeechPolicy.SEGMENT_HARD_CAP_SAMPLES
        val pause = soft - 1_000
        val atPause = ContinuousSpeechPolicy.planSegmentSplit(soft, pause)!!
        assertTrue(atPause.atSafePause)
        assertEquals(pause, atPause.splitSample)
        assertTrue(atPause.overlap.lengthSamples <= ContinuousSpeechPolicy.MAX_OVERLAP_SAMPLES)
        val forced = ContinuousSpeechPolicy.planSegmentSplit(soft + 50_000, null)!!
        assertFalse(forced.atSafePause)
        assertEquals(hard, forced.splitSample)
        // Safe pause outside the bounded window is ignored, not honored.
        val stale = ContinuousSpeechPolicy.planSegmentSplit(soft, 1_000)!!
        assertFalse(stale.atSafePause)
    }

    @Test
    fun reconcileDedupesOnlyVerifiedOverlap() {
        val hit = ContinuousSpeechPolicy.reconcile("the quick brown fox", "brown fox jumps", 2, null)
        assertTrue(hit.overlapDeduped)
        assertEquals("the quick brown fox jumps", hit.text)
        val miss = ContinuousSpeechPolicy.reconcile("alpha beta", "gamma delta", 2, null)
        assertFalse(miss.overlapDeduped)
        assertEquals("alpha beta gamma delta", miss.text)
        // Fully duplicated tail collapses to the head, never to empty.
        val full = ContinuousSpeechPolicy.reconcile("hello world", "hello world", 2, null)
        assertTrue(full.overlapDeduped)
        assertEquals("hello world", full.text)
    }

    @Test
    fun sessionCaps() {
        assertFalse(ContinuousSpeechPolicy.sessionSoftCapReached(ContinuousSpeechPolicy.SESSION_SOFT_CAP_SAMPLES - 1))
        assertTrue(ContinuousSpeechPolicy.sessionSoftCapReached(ContinuousSpeechPolicy.SESSION_SOFT_CAP_SAMPLES))
        assertFalse(ContinuousSpeechPolicy.sessionLimitReached(ContinuousSpeechPolicy.SESSION_HARD_CAP_SAMPLES - 1))
        assertTrue(ContinuousSpeechPolicy.sessionLimitReached(ContinuousSpeechPolicy.SESSION_HARD_CAP_SAMPLES))
        assertEquals(960_000, ContinuousSpeechPolicy.SEGMENT_HARD_CAP_BYTES)
    }

    @Test
    fun defaultEndpointConfigMatchesPlanBounds() {
        assertEquals(650L, EndpointConfig().endpointSilenceMs)
        // Plan §5.2: never force a universal ~150 ms endpoint without evidence —
        // the default debounce must stay well above it.
        assertEquals(1500L, EndpointConfig().debounceAfterEndpointMs)
    }

    // --- Overlap-search reconciliation (P05 split continuation) ---

    @Test
    fun overlapSearch_dedupesLongestExactMatch() {
        val r = ContinuousSpeechPolicy.reconcileWithOverlapSearch(
            "we are going to the store tomorrow",
            "to the store tomorrow morning we go",
        )
        assertTrue(r.overlapDeduped)
        assertEquals("we are going to the store tomorrow morning we go", r.text)
    }

    @Test
    fun overlapSearch_keepsBothOnMismatch() {
        // No tail/head N>=2 matches ("the store" vs "to the"): both kept.
        val r = ContinuousSpeechPolicy.reconcileWithOverlapSearch(
            "going to the store",
            "to the shop now open",
        )
        assertFalse(r.overlapDeduped)
        assertEquals("going to the store to the shop now open", r.text)
    }

    @Test
    fun overlapSearch_singleWordRepeatNeverEaten() {
        val r = ContinuousSpeechPolicy.reconcileWithOverlapSearch(
            "see you later",
            "later alligator",
        )
        assertFalse(r.overlapDeduped) // only 1-word match: kept, never eaten
        assertEquals("see you later later alligator", r.text)
    }

    @Test
    fun overlapSearch_respectsMaxWords() {
        val earlier = "a b c d e f g h i j"
        val later = "h i j k l"
        // Full 3-word match "h i j" is within default max 8.
        val r = ContinuousSpeechPolicy.reconcileWithOverlapSearch(earlier, later)
        assertTrue(r.overlapDeduped)
        assertEquals("a b c d e f g h i j k l", r.text)
        // Capped at 2, the 3-word overlap is out of reach ("i j" != "h i"):
        // both sides kept rather than partially eaten.
        val capped = ContinuousSpeechPolicy.reconcileWithOverlapSearch(earlier, later, maxOverlapWords = 2)
        assertFalse(capped.overlapDeduped)
        assertEquals("a b c d e f g h i j h i j k l", capped.text)
    }

    @Test
    fun overlapSearch_blankSides() {
        assertEquals("hello", ContinuousSpeechPolicy.reconcileWithOverlapSearch("", "hello").text)
        assertEquals("hello", ContinuousSpeechPolicy.reconcileWithOverlapSearch("hello", "").text)
        assertFalse(ContinuousSpeechPolicy.reconcileWithOverlapSearch("", "hello").overlapDeduped)
    }

    @Test
    fun segmentSplit_safePausePreferred_hardCapForced() {
        val soft = ContinuousSpeechPolicy.SEGMENT_SOFT_CAP_SAMPLES
        val hard = ContinuousSpeechPolicy.SEGMENT_HARD_CAP_SAMPLES
        val overlap = ContinuousSpeechPolicy.MAX_OVERLAP_SAMPLES
        assertNull(ContinuousSpeechPolicy.planSegmentSplit(soft - 1, null))
        // Safe pause inside the overlap window below the hard cap wins.
        val atPause = ContinuousSpeechPolicy.planSegmentSplit(soft + 1000, soft - 500)
        assertNotNull(atPause)
        assertTrue(atPause!!.atSafePause)
        assertEquals(soft - 500, atPause.splitSample)
        // No pause: hard cap forced once reached.
        val atHard = ContinuousSpeechPolicy.planSegmentSplit(hard + 8000, null)
        assertNotNull(atHard)
        assertFalse(atHard!!.atSafePause)
        assertEquals(hard, atHard.splitSample)
        assertEquals(hard - overlap, atHard.overlap.startSampleInclusive)
    }
}
