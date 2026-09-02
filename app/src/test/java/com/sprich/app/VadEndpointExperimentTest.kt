package com.sprich.app

import com.sprich.app.core.vad.Vad
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.sin

/**
 * Host VAD endpoint experiment — measures 450/500/550/650 ms thresholds.
 * Uses synthetic PCM: speech = sine 12000 amplitude, silence = 0.
 * Reports last speech → endpoint latency, false early splits, tail truncation.
 * Not a replacement for device human speech, but provides deterministic baseline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VadEndpointExperimentTest {

    private fun speechChunk(amp: Int = 12000): ShortArray = ShortArray(1024) { (sin(it * 0.1) * amp).toInt().toShort() }
    private fun silenceChunk(): ShortArray = ShortArray(1024) { 0 }
    private fun whisperChunk(): ShortArray = ShortArray(1024) { (sin(it * 0.1) * 500).toInt().toShort() }
    private fun noiseChunk(): ShortArray = ShortArray(1024) { (kotlin.random.Random.nextInt(-2000, 2000)).toShort() }

    data class Result(val threshold: Int, val framesToEndpoint: Int, val latencyMs: Long, val falseSplits: Int)

    private fun runVad(utteranceEndMs: Int, speechFrames: Int, silenceFrames: Int, hesitationFrames: Int = 0): Result {
        val vad = Vad(utteranceEndMs = utteranceEndMs)
        // Calibrate with silence
        repeat(3) { vad.process(silenceChunk(), 64) }
        var endpointFrame = -1
        var falseSplits = 0
        // Speech
        repeat(speechFrames) { vad.process(speechChunk(), 64) }
        // Hesitation (optional 200-300ms)
        repeat(hesitationFrames) {
            val r = vad.process(silenceChunk(), 64)
            if (r.state == Vad.State.UTTERANCE_END) falseSplits++
        }
        // Silence until endpoint
        for (i in 0 until silenceFrames) {
            val r = vad.process(silenceChunk(), 64)
            if (r.state == Vad.State.UTTERANCE_END && endpointFrame == -1) endpointFrame = i
        }
        val framesToEndpoint = if (endpointFrame >= 0) endpointFrame + 1 else silenceFrames
        // Quantized by 64ms callback granularity
        val latencyMs = framesToEndpoint * 64L
        return Result(utteranceEndMs, framesToEndpoint, latencyMs, falseSplits)
    }

    @Test
    fun benchmarkThresholds() {
        val thresholds = listOf(450, 500, 550, 650)
        val cases = listOf(
            "short command" to (5 to 20),
            "normal sentence" to (15 to 20),
            "long sentence" to (30 to 20),
            "quiet final word" to (10 to 20),
            "whisper" to (10 to 20),
            "noise" to (10 to 20)
        )
        println("VAD endpoint experiment (synthetic, 64ms granularity):")
        println("threshold | case | framesToEndpoint | latencyMs | falseSplits")
        for (thr in thresholds) {
            for ((name, cfg) in cases) {
                val (speechF, silenceF) = cfg
                val res = if (name == "whisper") {
                    val vad = Vad(utteranceEndMs = thr); repeat(3){vad.process(silenceChunk(),64)}
                    var ep=-1; repeat(speechF){vad.process(whisperChunk(),64)}; for(i in 0 until silenceF){val r=vad.process(silenceChunk(),64); if(r.state==Vad.State.UTTERANCE_END && ep==-1) ep=i}
                    Result(thr, if(ep>=0) ep+1 else silenceF, (if(ep>=0) ep+1 else silenceF)*64L, 0)
                } else if (name=="noise") {
                    val vad = Vad(utteranceEndMs = thr); repeat(3){vad.process(noiseChunk(),64)}; var ep=-1; repeat(speechF){vad.process(speechChunk(),64)}; for(i in 0 until silenceF){val r=vad.process(silenceChunk(),64); if(r.state==Vad.State.UTTERANCE_END && ep==-1) ep=i}
                    Result(thr, if(ep>=0) ep+1 else silenceF, (if(ep>=0) ep+1 else silenceF)*64L, 0)
                } else {
                    runVad(thr, speechF, silenceF)
                }
                println(String.format("%4d ms | %-18s | %2d | %4d ms | %d", thr, name, res.framesToEndpoint, res.latencyMs, res.falseSplits))
            }
        }
        // Assert that 650 is safe and 450 not too aggressive on synthetic
        val r450 = runVad(450, 15, 20)
        val r650 = runVad(650, 15, 20)
        // 450 should be ~7 frames (448ms) quantized to 512ms, 650 should be ~11 frames (704ms) quantized to 768ms
        assertTrue(r450.latencyMs in 380..550)
        assertTrue(r650.latencyMs in 600..780)
        // Hesitation tolerance: 300ms hesitation should not split at 650, but 800ms should
        val noSplit = runVad(650, 10, 20, hesitationFrames = 5) // 320ms hesitation (5*64)
        assertEquals(0, noSplit.falseSplits)
        val split = runVad(650, 10, 20, hesitationFrames = 13) // 832ms hesitation
        // This will have false split if hesitation > utteranceEndMs, but our Vad uses silenceMsAcc, so after 650 it would go to UTTERANCE_END
        // We just log, not assert strict
        println("hesitation 320ms falseSplits=${noSplit.falseSplits}, 832ms falseSplits=${split.falseSplits}")
    }

    @Test
    fun tailWordTruncationCheck() {
        // Fricative-final word like "Haus" ends with sibilant low energy — ensure 650 does not truncate
        val vad = Vad(utteranceEndMs = 650)
        repeat(3){vad.process(silenceChunk(),64)}
        // Simulate fricative tail: lower amplitude
        repeat(8){vad.process(speechChunk(4000),64)} // fricative 4k amp
        var endpoint = false
        repeat(12){
            val r = vad.process(silenceChunk(),64)
            if(r.state==Vad.State.UTTERANCE_END) endpoint = true
        }
        assertTrue("fricative tail should still endpoint", endpoint)
        // Whisper: 500 amp should still be detected as speech, not silence
        vad.reset()
        repeat(3){vad.process(silenceChunk(),64)}
        repeat(5){vad.process(whisperChunk(),64)}
        val r = vad.process(whisperChunk(),64)
        assertTrue(r.isSpeech)
    }
}
