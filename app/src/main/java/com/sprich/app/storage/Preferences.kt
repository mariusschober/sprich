package com.sprich.app.storage

import android.content.Context
import com.sprich.app.api.*
import com.sprich.app.speech.TranscriptionMode
import com.sprich.app.speech.refinement.RefinementMode
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.sprich.app.speech.api.EngineType
import com.sprich.app.speech.api.Language
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.speech.api.TranscriptionTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import androidx.datastore.preferences.core.emptyPreferences

private val Context.ds by preferencesDataStore("sprich_prefs")

class Preferences(context: Context) {
    private val context = context.applicationContext
    companion object {
        val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val KEY_INSTANT_MODE = booleanPreferencesKey("instant_mode")
        val KEY_WHISPER_MODE = booleanPreferencesKey("whisper_mode")
        val KEY_LANGUAGE = stringPreferencesKey("language")
        val KEY_ENGINE = stringPreferencesKey("engine")
        val KEY_HAPTICS = booleanPreferencesKey("haptics")
        val KEY_COMMANDS = booleanPreferencesKey("commands")
        val KEY_SILENCE_MODE = stringPreferencesKey("silence_mode") // auto, manual
        val KEY_VOCAB = stringPreferencesKey("vocab_json")
        val KEY_LEARN_CORRECTIONS = booleanPreferencesKey("learn_corrections")
        // Legacy backup remote STT (OpenAI-compatible /audio/transcriptions: Grok x.ai, Groq, fal Wizper, custom)
        val KEY_STT_MODE = stringPreferencesKey("stt_mode") // local | fallback | remote (legacy raw)
        val KEY_STT_BASE_URL = stringPreferencesKey("stt_base_url")
        val KEY_STT_API_KEY = stringPreferencesKey("stt_api_key") // legacy plaintext — migrated to ApiSecretStore, do NOT use for new code
        val KEY_STT_MODEL = stringPreferencesKey("stt_model")
        // Legacy AI polish (OpenAI-compatible /chat/completions)
        val KEY_AI_ENABLED = booleanPreferencesKey("ai_polish_enabled")
        val KEY_AI_BASE_URL = stringPreferencesKey("ai_base_url")
        val KEY_AI_API_KEY = stringPreferencesKey("ai_api_key")
        val KEY_AI_MODEL = stringPreferencesKey("ai_model")
        // Typed product semantics (new)
        val KEY_TRANSCRIPTION_MODE = stringPreferencesKey("transcription_mode") // on_device | api_primary | local_api_fallback
        val KEY_STT_PROVIDER_ID = stringPreferencesKey("stt_provider_id") // meta-muse-voice-transcribe | gemini | openai-compatible | custom | mock
        val KEY_STT_CREDENTIAL_REF = stringPreferencesKey("stt_credential_ref")
        val KEY_STT_DEADLINE_MS = longPreferencesKey("stt_deadline_ms")
        val KEY_STT_LANGUAGE_POLICY = stringPreferencesKey("stt_language_policy") // auto | fixed tag
        val KEY_STT_STREAMING_ENABLED = booleanPreferencesKey("stt_streaming_enabled")
        val KEY_STT_DETECT_TURNS = booleanPreferencesKey("stt_detect_turns")
        val KEY_STT_SPEAKERS = booleanPreferencesKey("stt_speaker_labels")
        val KEY_STT_LANGUAGE_HINTS = stringSetPreferencesKey("stt_language_hints")
        val KEY_REFINEMENT_MODE = stringPreferencesKey("refinement_mode") // off | correct | clean_dictation
        val KEY_REFINEMENT_PROVIDER_ID = stringPreferencesKey("refinement_provider_id")
        val KEY_REFINEMENT_CREDENTIAL_REF = stringPreferencesKey("refinement_credential_ref")
        val KEY_REFINEMENT_BASE_URL = stringPreferencesKey("refinement_base_url")
        val KEY_REFINEMENT_MODEL = stringPreferencesKey("refinement_model")
        val KEY_PERSONAL_VOCAB_HINT = booleanPreferencesKey("personal_vocab_hint_enabled")
        val KEY_STT_VERIFICATION = stringPreferencesKey("stt_connection_verification")
        val KEY_REFINEMENT_VERIFICATION = stringPreferencesKey("writing_connection_verification")
        val KEY_REFINEMENT_CONTEXT = booleanPreferencesKey("writing_field_context")
        val KEY_API_LOCAL_FALLBACK = booleanPreferencesKey("api_local_fallback")
        // Debug harness (opt-in only, default false, never logs transcript by default)
        val KEY_DEBUG_WAV_CAPTURE = booleanPreferencesKey("debug_wav_capture")
        val KEY_DEBUG_TRANSCRIPT_TRACE = booleanPreferencesKey("debug_transcript_trace")

        /**
         * Instant start is the first-run experience. A completed installation with no stored
         * value predates this default, so it stays off instead of silently opening its
         * microphone after an update. Any explicit choice always wins.
         */
        internal fun resolveInstantMode(prefs: androidx.datastore.preferences.core.Preferences): Boolean =
            prefs[KEY_INSTANT_MODE] ?: (prefs[KEY_ONBOARDING_DONE] != true)

    }

    val onboardingDone: Flow<Boolean> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_ONBOARDING_DONE] ?: false }
    val instantMode: Flow<Boolean> = context.ds.data.map(::resolveInstantMode).catch { if (it is IOException) emit(false) else throw it }
    val language: Flow<Language> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{
        when(it[KEY_LANGUAGE]?.lowercase()){
            "en", "en-us", "en-gb" -> Language.EN; "de", "de-de" -> Language.DE; "es", "es-es" -> Language.ES; "fr", "fr-fr" -> Language.FR; else -> Language.AUTO
        }
    }
    // Typed BCP-47 language, resolved once per session, observable in diagnostics.
    val speechLanguage: Flow<SpeechLanguage> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{
        val raw = it[KEY_LANGUAGE]?.lowercase()?.trim().orEmpty()
        when(raw){
            "auto", "" -> SpeechLanguage.Auto
            "en", "en-us", "en-gb" -> SpeechLanguage.Fixed("en")
            "de", "de-de" -> SpeechLanguage.Fixed("de")
            "es", "es-es" -> SpeechLanguage.Fixed("es")
            "fr", "fr-fr" -> SpeechLanguage.Fixed("fr")
            else -> if (raw.matches(Regex("[a-z]{2}(-[a-z0-9]+)?"))) SpeechLanguage.Fixed(raw) else SpeechLanguage.Auto
        }
    }
    val engineType: Flow<EngineType> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{
        when(it[KEY_ENGINE]){
            "accurate" -> EngineType.ACCURATE; "streaming" -> EngineType.STREAMING; else -> EngineType.FAST
        }
    }
    val haptics: Flow<Boolean> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_HAPTICS] ?: true }
    val commands: Flow<Boolean> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_COMMANDS] ?: true }

    enum class SttMode { LOCAL, FALLBACK, REMOTE }
    enum class AiMode { OFF, REMOTE }

    val sttModeRaw: Flow<String> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_STT_MODE] ?: "local" }
    val sttBaseUrl: Flow<String> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_STT_BASE_URL] ?: "" }
    val sttApiKey: Flow<String> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_STT_API_KEY] ?: "" }
    val sttModel: Flow<String> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_STT_MODEL] ?: "whisper-large-v3" }

    val aiEnabled: Flow<Boolean> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_AI_ENABLED] ?: false }
    val aiBaseUrl: Flow<String> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_AI_BASE_URL] ?: "" }
    val aiApiKey: Flow<String> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_AI_API_KEY] ?: "" }
    val aiModel: Flow<String> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_AI_MODEL] ?: "" }

    // Typed flows — new product semantics, with legacy fallback
    val transcriptionMode: Flow<com.sprich.app.speech.TranscriptionMode> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map { prefs ->
        prefs[KEY_TRANSCRIPTION_MODE]?.let {
            try { com.sprich.app.speech.TranscriptionMode.valueOf(it) } catch (_: Exception) { null }
        } ?: com.sprich.app.speech.TranscriptionMode.fromRaw(prefs[KEY_STT_MODE] ?: "local")
    }
    val sttProviderId: Flow<String> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_STT_PROVIDER_ID] ?: "meta-muse-voice-transcribe" }
    val sttCredentialRef: Flow<String> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_STT_CREDENTIAL_REF] ?: "" }
    val sttDeadlineMs: Flow<Long> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_STT_DEADLINE_MS] ?: 3500L }
    val sttLanguagePolicyRaw: Flow<String> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_STT_LANGUAGE_POLICY] ?: "auto" }
    val sttStreamingEnabled: Flow<Boolean> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_STT_STREAMING_ENABLED] ?: false }

    val refinementMode: Flow<com.sprich.app.speech.refinement.RefinementMode> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map { prefs ->
        prefs[KEY_REFINEMENT_MODE]?.let {
            try { com.sprich.app.speech.refinement.RefinementMode.valueOf(it) } catch (_: Exception) { com.sprich.app.speech.refinement.RefinementMode.fromRaw(it) }
        } ?: if (prefs[KEY_AI_ENABLED] == true) com.sprich.app.speech.refinement.RefinementMode.CORRECT else com.sprich.app.speech.refinement.RefinementMode.OFF
    }
    val refinementProviderId: Flow<String> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_REFINEMENT_PROVIDER_ID] ?: "openai-compatible" }
    val refinementCredentialRef: Flow<String> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_REFINEMENT_CREDENTIAL_REF] ?: "refine_default" }
    val refinementBaseUrl: Flow<String> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_REFINEMENT_BASE_URL] ?: it[KEY_AI_BASE_URL] ?: "" }
    val refinementModel: Flow<String> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_REFINEMENT_MODEL] ?: it[KEY_AI_MODEL] ?: "" }
    val personalVocabHintEnabled: Flow<Boolean> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_PERSONAL_VOCAB_HINT] ?: false }

    val debugWavCapture: Flow<Boolean> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_DEBUG_WAV_CAPTURE] ?: false }
    val debugTranscriptTrace: Flow<Boolean> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_DEBUG_TRANSCRIPT_TRACE] ?: false }

    // Single atomic snapshot — one DataStore emission maps to one immutable RuntimeConfigSnapshot.
    // IME must copy one snapshot at speech onset; Settings mutations affect only next utterance.
    val runtimeConfigSnapshot: Flow<RuntimeConfigSnapshot> = context.ds.data.catch {
        // A storage failure is not first-run consent. Preserve the established config fallbacks,
        // but synthesize a completed install so automatic microphone start fails closed.
        if (it is IOException) emit(mutablePreferencesOf(KEY_ONBOARDING_DONE to true)) else throw it
    }.map { prefs ->
        val transcriptionMode = prefs[KEY_TRANSCRIPTION_MODE]?.let {
            try { com.sprich.app.speech.TranscriptionMode.valueOf(it) } catch (_: Exception) { null }
        } ?: com.sprich.app.speech.TranscriptionMode.fromRaw(prefs[KEY_STT_MODE] ?: "local")
        val speechLanguage = run {
            val raw = prefs[KEY_LANGUAGE]?.lowercase()?.trim().orEmpty()
            when (raw) {
                "auto", "" -> SpeechLanguage.Auto
                "en", "en-us", "en-gb" -> SpeechLanguage.Fixed("en")
                "de", "de-de" -> SpeechLanguage.Fixed("de")
                "es", "es-es" -> SpeechLanguage.Fixed("es")
                "fr", "fr-fr" -> SpeechLanguage.Fixed("fr")
                else -> if (raw.matches(Regex("[a-z]{2}(-[a-z0-9]+)?"))) SpeechLanguage.Fixed(raw) else SpeechLanguage.Auto
            }
        }
        val refinementMode = prefs[KEY_REFINEMENT_MODE]?.let {
            try { com.sprich.app.speech.refinement.RefinementMode.valueOf(it) } catch (_: Exception) { com.sprich.app.speech.refinement.RefinementMode.fromRaw(it) }
        } ?: if (prefs[KEY_AI_ENABLED] == true) com.sprich.app.speech.refinement.RefinementMode.CORRECT else com.sprich.app.speech.refinement.RefinementMode.OFF
        RuntimeConfigSnapshot(
            transcriptionMode = transcriptionMode,
            speechLanguage = speechLanguage,
            refinementMode = refinementMode,
            sttProviderId = prefs[KEY_STT_PROVIDER_ID] ?: "meta-muse-voice-transcribe",
            sttBaseUrl = prefs[KEY_STT_BASE_URL] ?: "",
            sttModel = prefs[KEY_STT_MODEL] ?: "whisper-large-v3",
            sttCredentialRef = prefs[KEY_STT_CREDENTIAL_REF] ?: "",
            sttDeadlineMs = prefs[KEY_STT_DEADLINE_MS] ?: 3500L,
            sttStreamingEnabled = prefs[KEY_STT_STREAMING_ENABLED] ?: false,
            sttLanguagePolicyRaw = prefs[KEY_STT_LANGUAGE_POLICY] ?: "auto",
            refinementProviderId = prefs[KEY_REFINEMENT_PROVIDER_ID] ?: "openai-compatible",
            refinementCredentialRef = prefs[KEY_REFINEMENT_CREDENTIAL_REF] ?: "refine_default",
            refinementBaseUrl = prefs[KEY_REFINEMENT_BASE_URL] ?: prefs[KEY_AI_BASE_URL] ?: "",
            refinementModel = prefs[KEY_REFINEMENT_MODEL] ?: prefs[KEY_AI_MODEL] ?: "",
            personalVocabHintEnabled = prefs[KEY_PERSONAL_VOCAB_HINT] ?: false,
            debugWavCapture = prefs[KEY_DEBUG_WAV_CAPTURE] ?: false,
            debugTranscriptTrace = prefs[KEY_DEBUG_TRANSCRIPT_TRACE] ?: false,
            instantMode = resolveInstantMode(prefs),
            commandsEnabled = prefs[KEY_COMMANDS] ?: true,
            hapticsEnabled = prefs[KEY_HAPTICS] ?: true,
            sttVerification = prefs[KEY_STT_VERIFICATION] ?: "",
            refinementVerification = prefs[KEY_REFINEMENT_VERIFICATION] ?: "",
            refinementContextEnabled = prefs[KEY_REFINEMENT_CONTEXT] ?: false,
            apiLocalFallback = prefs[KEY_API_LOCAL_FALLBACK] ?: false,
            voiceApiOptions = voiceOptions(prefs),
            whisperMode = prefs[KEY_WHISPER_MODE] ?: false,
        ).enforceProviderAvailability()
    }

    private fun apiChoice(p: androidx.datastore.preferences.core.Preferences, use: ApiUse): ApiChoice = when (use) {
        ApiUse.VOICE -> ApiChoice(use, p[KEY_STT_PROVIDER_ID] ?: "", p[KEY_STT_BASE_URL] ?: "", p[KEY_STT_MODEL] ?: "", p[KEY_STT_CREDENTIAL_REF] ?: "", p[KEY_STT_VERIFICATION] ?: "", voiceOptions(p))
        ApiUse.WRITING -> ApiChoice(use, p[KEY_REFINEMENT_PROVIDER_ID] ?: "", p[KEY_REFINEMENT_BASE_URL] ?: "", p[KEY_REFINEMENT_MODEL] ?: "", p[KEY_REFINEMENT_CREDENTIAL_REF] ?: "", p[KEY_REFINEMENT_VERIFICATION] ?: "")
    }
    /** Persisted fields, including an empty first-run choice, for a connection check's compare-and-set. */
    suspend fun apiChoiceForCheck(use: ApiUse): ApiChoice = apiChoice(context.ds.data.first(), use)
    private fun voiceOptions(p: androidx.datastore.preferences.core.Preferences) = com.sprich.app.speech.remote.VoiceApiOptions(
        streaming = p[KEY_STT_STREAMING_ENABLED] ?: false,
        detectTurns = p[KEY_STT_DETECT_TURNS] ?: true,
        speakerLabels = p[KEY_STT_SPEAKERS] ?: false,
        languageHints = p[KEY_STT_LANGUAGE_HINTS].orEmpty().toSet(),
    )

    private fun writeApiChoice(p: MutablePreferences, choice: ApiChoice) {
        when (choice.use) {
            ApiUse.VOICE -> {
                p[KEY_TRANSCRIPTION_MODE] = TranscriptionMode.ON_DEVICE.name
                p[KEY_STT_MODE] = "local"
                p[KEY_STT_PROVIDER_ID] = choice.providerId
                p[KEY_STT_BASE_URL] = choice.endpoint.trimEnd('/')
                p[KEY_STT_MODEL] = choice.model
                p[KEY_STT_CREDENTIAL_REF] = choice.credentialRef
                p[KEY_STT_VERIFICATION] = ""
                p[KEY_STT_STREAMING_ENABLED] = choice.voiceOptions.streaming
                p[KEY_STT_DETECT_TURNS] = choice.voiceOptions.detectTurns
                p[KEY_STT_SPEAKERS] = choice.voiceOptions.speakerLabels
                p[KEY_STT_LANGUAGE_HINTS] = choice.voiceOptions.languageHints.toSet()
                p[KEY_STT_DEADLINE_MS] = 3500L
            }
            ApiUse.WRITING -> {
                val old = apiChoice(p, choice.use)
                if (old.providerId != choice.providerId || old.endpoint != choice.endpoint) p[KEY_REFINEMENT_CONTEXT] = false
                p[KEY_REFINEMENT_MODE] = RefinementMode.OFF.name
                p[KEY_AI_ENABLED] = false
                p[KEY_REFINEMENT_PROVIDER_ID] = choice.providerId
                p[KEY_REFINEMENT_BASE_URL] = choice.endpoint.trimEnd('/')
                p[KEY_REFINEMENT_MODEL] = choice.model
                p[KEY_REFINEMENT_CREDENTIAL_REF] = choice.credentialRef
                p[KEY_REFINEMENT_VERIFICATION] = ""
            }
        }
        p.remove(KEY_STT_API_KEY)
        p.remove(KEY_AI_API_KEY)
    }

    /** A failed check leaves the working choice intact. Success saves proof, never new permission. */
    suspend fun commitApiCheck(choice: ApiChoice, previous: ApiChoice, expectedEpoch: Long): Boolean {
        require(choice.valid && choice.use == previous.use) { "Invalid API configuration" }
        var saved = false
        context.ds.edit { p ->
            // Use the same monitor as revoke: a late response cannot race a removed key or cleared data.
            synchronized(ApiHttp) {
                val current = apiChoice(p, choice.use)
                if (ApiHttp.currentEpoch == expectedEpoch && current == previous) {
                    if (current.fingerprint() != choice.fingerprint() || !current.verified) {
                        ApiHttp.revoke()
                        writeApiChoice(p, choice)
                    }
                    p[if (choice.use == ApiUse.VOICE) KEY_STT_VERIFICATION else KEY_REFINEMENT_VERIFICATION] = choice.fingerprint()
                    saved = true
                }
            }
        }
        return saved
    }

    suspend fun setApiEnabled(use: ApiUse, enabled: Boolean) {
        if (!enabled) ApiHttp.revoke()
        context.ds.edit { p ->
            require(!enabled || apiChoice(p, use).verified) { "Check this API before enabling it" }
            if (use == ApiUse.VOICE) {
                p[KEY_TRANSCRIPTION_MODE] = (if (enabled) TranscriptionMode.API_PRIMARY else TranscriptionMode.ON_DEVICE).name
                p[KEY_STT_MODE] = if (enabled) "remote" else "local"
            } else {
                p[KEY_REFINEMENT_MODE] = (if (enabled) RefinementMode.CLEAN_DICTATION else RefinementMode.OFF).name
                p[KEY_AI_ENABLED] = enabled
            }
        }
    }

    suspend fun removeApi(use: ApiUse): String {
        ApiHttp.revoke()
        var orphan = ""
        context.ds.edit { p ->
            val old = apiChoice(p, use)
            val other = apiChoice(p, if (use == ApiUse.VOICE) ApiUse.WRITING else ApiUse.VOICE)
            if (old.credentialRef != other.credentialRef) orphan = old.credentialRef
            if (use == ApiUse.VOICE) {
                p[KEY_TRANSCRIPTION_MODE] = TranscriptionMode.ON_DEVICE.name
                p[KEY_STT_MODE] = "local"
                p.remove(KEY_STT_CREDENTIAL_REF); p.remove(KEY_STT_VERIFICATION)
            } else {
                p[KEY_REFINEMENT_MODE] = RefinementMode.OFF.name
                p[KEY_AI_ENABLED] = false
                p.remove(KEY_REFINEMENT_CREDENTIAL_REF); p.remove(KEY_REFINEMENT_VERIFICATION)
                p.remove(KEY_REFINEMENT_CONTEXT)
            }
        }
        return orphan
    }

    suspend fun setRefinementContext(enabled: Boolean) {
        if (!enabled) ApiHttp.revoke()
        context.ds.edit { it[KEY_REFINEMENT_CONTEXT] = enabled }
    }
    suspend fun setApiLocalFallback(enabled: Boolean) { context.ds.edit { it[KEY_API_LOCAL_FALLBACK] = enabled } }

    suspend fun setSttMode(v: SttMode){ context.ds.edit{it[KEY_STT_MODE]= when(v){ SttMode.LOCAL->"local"; SttMode.FALLBACK->"fallback"; SttMode.REMOTE->"remote" }}}
    suspend fun setSttBaseUrl(v: String){ context.ds.edit{it[KEY_STT_BASE_URL]=v.trim().trimEnd('/')} }
    @Deprecated("Legacy plaintext — use ApiSecretStore; migration-only")
    suspend fun setSttApiKey(v: String){ context.ds.edit{it[KEY_STT_API_KEY]=v.trim()} }
    suspend fun setSttModel(v: String){ context.ds.edit{it[KEY_STT_MODEL]=v.trim()} }
    suspend fun setTranscriptionMode(v: com.sprich.app.speech.TranscriptionMode){ context.ds.edit{it[KEY_TRANSCRIPTION_MODE]= v.name; it[KEY_STT_MODE]= com.sprich.app.speech.TranscriptionMode.toRaw(v)} }
    suspend fun setSttProviderId(v: String){ context.ds.edit{it[KEY_STT_PROVIDER_ID]= v} }
    suspend fun setSttCredentialRef(v: String){ context.ds.edit{it[KEY_STT_CREDENTIAL_REF]= v} }
    suspend fun setSttDeadlineMs(v: Long){ context.ds.edit{it[KEY_STT_DEADLINE_MS]= v} }
    suspend fun setSttStreamingEnabled(v: Boolean){ context.ds.edit{it[KEY_STT_STREAMING_ENABLED]= v} }
    suspend fun setAiEnabled(v: Boolean){ context.ds.edit{it[KEY_AI_ENABLED]=v} }
    suspend fun setAiBaseUrl(v: String){ context.ds.edit{it[KEY_AI_BASE_URL]=v.trim().trimEnd('/')} }
    @Deprecated("Legacy plaintext — use ApiSecretStore; migration-only")
    suspend fun setAiApiKey(v: String){ context.ds.edit{it[KEY_AI_API_KEY]=v.trim()} }
    suspend fun setAiModel(v: String){ context.ds.edit{it[KEY_AI_MODEL]=v.trim()} }
    suspend fun setRefinementMode(v: com.sprich.app.speech.refinement.RefinementMode){ context.ds.edit{it[KEY_REFINEMENT_MODE]= v.name; it[KEY_AI_ENABLED]= (v != com.sprich.app.speech.refinement.RefinementMode.OFF)} }
    suspend fun setRefinementProviderId(v: String){ context.ds.edit{it[KEY_REFINEMENT_PROVIDER_ID]= v} }
    suspend fun setRefinementCredentialRef(v: String){ context.ds.edit{it[KEY_REFINEMENT_CREDENTIAL_REF]= v} }
    suspend fun setRefinementBaseUrl(v: String){ context.ds.edit{it[KEY_REFINEMENT_BASE_URL]= v.trim().trimEnd('/')} }
    suspend fun setRefinementModel(v: String){ context.ds.edit{it[KEY_REFINEMENT_MODEL]= v.trim()} }
    suspend fun setPersonalVocabHintEnabled(v: Boolean){
        if (!v) ApiHttp.revoke()
        context.ds.edit{it[KEY_PERSONAL_VOCAB_HINT]= v}
    }
    suspend fun setDebugWavCapture(v: Boolean){ context.ds.edit{it[KEY_DEBUG_WAV_CAPTURE]=v} }
    suspend fun setDebugTranscriptTrace(v: Boolean){ context.ds.edit{it[KEY_DEBUG_TRANSCRIPT_TRACE]=v} }

    suspend fun clearLegacyApiKeys(){ context.ds.edit{ it.remove(KEY_STT_API_KEY); it.remove(KEY_AI_API_KEY) } }
    /**
     * Persist the first-run default before onboarding is drawn. Re-entering the app never
     * overwrites an opt-out, and legacy completed installations remain on their old default.
     * Returns whether onboarding was already complete.
     */
    suspend fun initializeFirstRun(): Boolean {
        var done = false
        context.ds.edit { stored ->
            done = stored[KEY_ONBOARDING_DONE] ?: false
            if (!done && !stored.contains(KEY_INSTANT_MODE)) stored[KEY_INSTANT_MODE] = true
        }
        return done
    }

    suspend fun setOnboardingDone(v: Boolean){
        context.ds.edit {
            if (v && !it.contains(KEY_INSTANT_MODE)) it[KEY_INSTANT_MODE] = true
            it[KEY_ONBOARDING_DONE] = v
        }
    }
    suspend fun setInstantMode(v: Boolean){ context.ds.edit{it[KEY_INSTANT_MODE]=v} }
    suspend fun setWhisperMode(v: Boolean) { context.ds.edit { it[KEY_WHISPER_MODE] = v } }
    suspend fun setLanguage(v: Language){ context.ds.edit{it[KEY_LANGUAGE]=v.code} }
    suspend fun setSpeechLanguage(v: SpeechLanguage){ context.ds.edit{it[KEY_LANGUAGE]= v.toBcp47()} }
    /** Explicit first-run suggestion from Locale.getDefault() — only called once during onboarding. */
    suspend fun suggestLanguageFromLocale(localeTag: String){
        val current = try { context.ds.data.map { it[KEY_LANGUAGE] }.first() } catch (_: Exception) { null }
        if (current.isNullOrBlank() || current == "auto") {
            val tag = localeTag.lowercase().take(5)
            val suggested = when {
                tag.startsWith("de") -> "de"
                tag.startsWith("es") -> "es"
                tag.startsWith("fr") -> "fr"
                tag.startsWith("en") -> "en"
                else -> "auto"
            }
            // Do not override explicit user choice; only suggest auto fallback remains auto unless user picks
            if (suggested != "auto") {
                // Keep as suggestion only if user hasn't chosen; we still store auto but onboarding UI can show suggestion
                // This method documents the only allowed Locale coupling point.
            }
        }
    }
    suspend fun setEngine(v: EngineType){ context.ds.edit{it[KEY_ENGINE]= when(v){ EngineType.FAST->"fast"; EngineType.ACCURATE->"accurate"; EngineType.STREAMING->"streaming" }}}
    suspend fun setHaptics(v: Boolean){ context.ds.edit{it[KEY_HAPTICS]=v} }
    suspend fun setCommands(v: Boolean){ context.ds.edit{it[KEY_COMMANDS]=v} }
    suspend fun clearAll(){ ApiHttp.revoke(); context.ds.edit{it.clear()}}
}

// Locked provider defaults — Muse Voice Transcribe (Meta Model API) + Gemini (top-level for Settings/SprichIME)
object MuseDefaults {
    const val PROVIDER_ID = "meta-muse-voice-transcribe"
    const val BASE_URL = "https://api.meta.ai"
    const val REALTIME_PATH = "/v1/asr/realtime"
    const val TRANSCRIBE_PATH = "/v1/asr/transcribe"
    const val MODEL = "muse-voice-transcribe-1.0"
    const val ENDPOINT_REALTIME = "wss://api.meta.ai/v1/asr/realtime"
    const val ENDPOINT_TRANSCRIBE = "https://api.meta.ai/v1/asr/transcribe"
}
object GeminiDefaults {
    const val PROVIDER_ID = "gemini"
    const val BASE_URL = "https://generativelanguage.googleapis.com"
    const val MODEL = "gemini-3.5-transcribe"
    const val MODEL_LIVE = "gemini-3.5-transcribe-live"
}
