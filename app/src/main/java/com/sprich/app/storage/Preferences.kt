package com.sprich.app.storage

import android.content.Context
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
        val KEY_LANGUAGE = stringPreferencesKey("language")
        val KEY_ENGINE = stringPreferencesKey("engine")
        val KEY_HAPTICS = booleanPreferencesKey("haptics")
        val KEY_COMMANDS = booleanPreferencesKey("commands")
        val KEY_SILENCE_MODE = stringPreferencesKey("silence_mode") // auto, manual
        val KEY_VOCAB = stringPreferencesKey("vocab_json")
        val KEY_LEARN_CORRECTIONS = booleanPreferencesKey("learn_corrections")
        // Backup remote STT (OpenAI-compatible /audio/transcriptions: Grok x.ai, Groq, fal Wizper, custom)
        val KEY_STT_MODE = stringPreferencesKey("stt_mode") // local | fallback | remote
        val KEY_STT_BASE_URL = stringPreferencesKey("stt_base_url")
        val KEY_STT_API_KEY = stringPreferencesKey("stt_api_key")
        val KEY_STT_MODEL = stringPreferencesKey("stt_model")
        // AI polish (OpenAI-compatible /chat/completions)
        val KEY_AI_ENABLED = booleanPreferencesKey("ai_polish_enabled")
        val KEY_AI_BASE_URL = stringPreferencesKey("ai_base_url")
        val KEY_AI_API_KEY = stringPreferencesKey("ai_api_key")
        val KEY_AI_MODEL = stringPreferencesKey("ai_model")
    }

    val onboardingDone: Flow<Boolean> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_ONBOARDING_DONE] ?: false }
    val instantMode: Flow<Boolean> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_INSTANT_MODE] ?: false }
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

    suspend fun setSttMode(v: SttMode){ context.ds.edit{it[KEY_STT_MODE]= when(v){ SttMode.LOCAL->"local"; SttMode.FALLBACK->"fallback"; SttMode.REMOTE->"remote" }}}
    suspend fun setSttBaseUrl(v: String){ context.ds.edit{it[KEY_STT_BASE_URL]=v.trim().trimEnd('/')} }
    suspend fun setSttApiKey(v: String){ context.ds.edit{it[KEY_STT_API_KEY]=v.trim()} }
    suspend fun setSttModel(v: String){ context.ds.edit{it[KEY_STT_MODEL]=v.trim()} }
    suspend fun setAiEnabled(v: Boolean){ context.ds.edit{it[KEY_AI_ENABLED]=v} }
    suspend fun setAiBaseUrl(v: String){ context.ds.edit{it[KEY_AI_BASE_URL]=v.trim().trimEnd('/')} }
    suspend fun setAiApiKey(v: String){ context.ds.edit{it[KEY_AI_API_KEY]=v.trim()} }
    suspend fun setAiModel(v: String){ context.ds.edit{it[KEY_AI_MODEL]=v.trim()} }

    suspend fun setOnboardingDone(v: Boolean){ context.ds.edit{it[KEY_ONBOARDING_DONE]=v} }
    suspend fun setInstantMode(v: Boolean){ context.ds.edit{it[KEY_INSTANT_MODE]=v} }
    suspend fun setLanguage(v: Language){ context.ds.edit{it[KEY_LANGUAGE]=v.code} }
    suspend fun setSpeechLanguage(v: SpeechLanguage){ context.ds.edit{it[KEY_LANGUAGE]= v.toBcp47()} }
    /** Explicit first-run suggestion from Locale.getDefault() — only called once during onboarding. */
    suspend fun suggestLanguageFromLocale(localeTag: String){
        val current = context.ds.data.map { it[KEY_LANGUAGE] }.let {
            // only suggest if still auto/empty
            try { kotlinx.coroutines.runBlocking { it.first() } } catch (_: Exception){ null }
        }
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
    suspend fun clearAll(){ context.ds.edit{it.clear()}}
}
