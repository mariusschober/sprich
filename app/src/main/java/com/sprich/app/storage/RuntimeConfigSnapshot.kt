package com.sprich.app.storage

import com.sprich.app.speech.TranscriptionMode
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.speech.refinement.RefinementMode

/**
 * One immutable runtime config — built from ONE DataStore Preferences emission.
 * One utterance copies one snapshot at speech onset; Settings mutations affect only next utterance.
 * This is the sole authority for route/provider/credential/render decisions for that utterance.
 */
data class RuntimeConfigSnapshot(
    val transcriptionMode: TranscriptionMode,
    val speechLanguage: SpeechLanguage,
    val refinementMode: RefinementMode,
    val sttProviderId: String,
    val sttBaseUrl: String,
    val sttModel: String,
    val sttCredentialRef: String,
    val sttDeadlineMs: Long,
    val sttStreamingEnabled: Boolean,
    val sttLanguagePolicyRaw: String,
    val refinementProviderId: String,
    val refinementCredentialRef: String,
    val refinementBaseUrl: String,
    val refinementModel: String,
    val refinementDeadlineMs: Long,
    val personalVocabHintEnabled: Boolean,
    val debugWavCapture: Boolean,
    val debugTranscriptTrace: Boolean,
    // Derived but frozen at snapshot time for audio hot path
    val instantMode: Boolean,
    val commandsEnabled: Boolean,
    val hapticsEnabled: Boolean,
) {
    // Cheap derived for logging without PII
    override fun toString(): String {
        return "RuntimeConfigSnapshot(mode=$transcriptionMode, speechLang=$speechLanguage, refine=$refinementMode, sttProvider=$sttProviderId, sttModel=$sttModel, streaming=$sttStreamingEnabled, instant=$instantMode)"
    }
}
