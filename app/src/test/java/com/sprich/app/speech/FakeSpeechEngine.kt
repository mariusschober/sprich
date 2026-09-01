package com.sprich.app.speech

import com.sprich.app.speech.api.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Test double for SpeechEngine — replaces production Canary mock.
 * Production Canary no longer returns fabricated transcripts; tests inject this fake.
 * Mimics old Canary mock timing and strings for host test invariants.
 */
class FakeSpeechEngine(
    override val engineId: String = "fake-canary",
    override val displayName: String = "Fake Canary",
) : SpeechEngine {
    private var loaded = true
    private var config: SpeechSessionConfig? = null
    private val flow = MutableSharedFlow<TranscriptUpdate>(replay = 0, extraBufferCapacity = 16)
    private val mutex = Mutex()
    @Volatile var decodeStarts: Long = 0
    @Volatile var curConc: Int = 0
    @Volatile var maxConc: Int = 0
    @Volatile var endCalls: Long = 0

    private val pcmBuffer = mutableListOf<Short>()

    override fun capabilities() = SpeechEngineCapabilities(partialResults = true, languageDetection = false, punctuation = true)
    override fun supportedLanguages() = setOf(Language.EN, Language.DE, Language.ES, Language.FR)
    override fun isLoaded() = loaded
    override suspend fun load(): Result<Unit> = Result.success(Unit)
    override suspend fun unload() { loaded = false; pcmBuffer.clear() }
    override fun beginSession(config: SpeechSessionConfig) { this.config = config; pcmBuffer.clear() }
    override fun pushAudio(samples: ShortArray, timestampNanos: Long) { synchronized(pcmBuffer) { for (s in samples) pcmBuffer.add(s) } }
    override fun partialTranscript(): Flow<TranscriptUpdate> = flow
    override suspend fun endUtterance(): FinalTranscript = mutex.withLock {
        endCalls++
        decodeStarts++
        curConc++
        maxConc = maxOf(maxConc, curConc)
        try { delay(5) } finally { curConc-- }
        val size = synchronized(pcmBuffer) { pcmBuffer.size }
        pcmBuffer.clear()
        val sec = size / 16000f
        val text = when {
            sec < 1f -> "Hello"
            sec < 2.5f -> "Hello world canary"
            else -> "Hello world canary accurate transcription"
        }
        // Even empty-ish short buffer still returns Hello if sec<1
        if (size == 0) return@withLock FinalTranscript("")
        return@withLock FinalTranscript(text)
    }
    // Snapshot variant for overlapping test
    suspend fun transcribeSnapshot(pcm: ShortArray, cfg: SpeechSessionConfig): FinalTranscript = mutex.withLock {
        decodeStarts++
        curConc++
        maxConc = maxOf(maxConc, curConc)
        try { delay(5) } finally { curConc-- }
        val sec = pcm.size / 16000f
        val text = when {
            pcm.isEmpty() -> ""
            sec < 1f -> "Hello"
            sec < 2.5f -> "Hello world canary"
            else -> "Hello world canary accurate transcription"
        }
        FinalTranscript(text)
    }
    override fun cancelSession() { pcmBuffer.clear() }
    override fun reset() = cancelSession()
}
