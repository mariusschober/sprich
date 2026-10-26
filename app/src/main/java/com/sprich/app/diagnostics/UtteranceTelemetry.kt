package com.sprich.app.diagnostics

/**
 * Truthful per-attempt utterance telemetry (plan §6.4). Pure JVM: no Android
 * imports, injectable monotonic clock for host tests.
 *
 * - Events are recorded at actual operation boundaries and carry utterance
 *   identity ([utteranceId]/[seq]) plus a monotonic timestamp, so queue age
 *   and per-stage timing are reconstructible.
 * - Counters count attempts, not only winners: a failed remote attempt
 *   followed by a local attempt records both ([REMOTE_ACK] with
 *   [UtteranceOutcome.TRANSPORT_FAILURE] and [RECOGNITION_END] with SUCCESS).
 * - [UtteranceOutcome] distinguishes NoSpeech from model load, inference,
 *   transport, malformed response, unsupported editor, and cancellation.
 * - Privacy by construction: events carry only ids, timestamps, attempt
 *   indexes and numeric codes. There is deliberately no transcript, prompt,
 *   API secret, endpoint, or surrounding-text field.
 */
enum class UtteranceOutcome {
    SUCCESS,
    NO_SPEECH,
    LOAD_FAILURE,
    INFERENCE_FAILURE,
    TRANSPORT_FAILURE,
    MALFORMED_RESPONSE,
    UNSUPPORTED_EDITOR,
    CANCELLED,
}

enum class TelemetryEventType {
    FOCUS,
    CAPTURE_REQUESTED,
    FIRST_PCM,
    SPEECH_ONSET,
    ENDPOINT,
    QUEUE_ADMISSION,
    RECOGNITION_START,
    RECOGNITION_END,
    REMOTE_CONNECT,
    REMOTE_ACK,
    UPLOAD_BACKLOG,
    FIRST_PREVIEW,
    PROVIDER_FINAL,
    CLEANUP_START,
    CLEANUP_END,
    CLEANUP_VALIDATED,
    COMMIT_ATTEMPT,
    COMMIT_RESULT,
    CANCELLATION,
    RELEASE,
}

data class TelemetryEvent(
    val utteranceId: Long,
    val seq: Long,
    val type: TelemetryEventType,
    val monoNanos: Long,
    val outcome: UtteranceOutcome? = null,
    val attemptIndex: Int = 0,
    val code: Int = 0,
)

class UtteranceTelemetry(
    private val maxEvents: Int = 256,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private val lock = Any()
    private val events = ArrayDeque<TelemetryEvent>()

    fun record(
        utteranceId: Long,
        seq: Long,
        type: TelemetryEventType,
        outcome: UtteranceOutcome? = null,
        attemptIndex: Int = 0,
        code: Int = 0,
        monoNanos: Long = clockNanos(),
    ): TelemetryEvent {
        val event = TelemetryEvent(utteranceId, seq, type, monoNanos, outcome, attemptIndex, code)
        synchronized(lock) {
            if (events.size >= maxEvents) events.removeFirst()
            events.addLast(event)
        }
        return event
    }

    /** Ordered events for one utterance (empty when unknown/expired). */
    fun snapshotFor(utteranceId: Long): List<TelemetryEvent> =
        synchronized(lock) { events.filter { it.utteranceId == utteranceId } }

    /** Number of recorded attempts of one type (attempts, not winners). */
    fun attemptCount(type: TelemetryEventType): Int =
        synchronized(lock) { events.count { it.type == type } }

    fun attemptCount(type: TelemetryEventType, utteranceId: Long): Int =
        synchronized(lock) { events.count { it.type == type && it.utteranceId == utteranceId } }

    /** Elapsed nanos between two event types for one utterance, or null. */
    fun elapsedNanos(utteranceId: Long, from: TelemetryEventType, to: TelemetryEventType): Long? =
        synchronized(lock) {
            val ordered = events.filter { it.utteranceId == utteranceId }
            val start = ordered.firstOrNull { it.type == from }?.monoNanos ?: return null
            val end = ordered.lastOrNull { it.type == to }?.monoNanos ?: return null
            end - start
        }

    fun size(): Int = synchronized(lock) { events.size }
}
