package com.sprich.app.storage

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.sprich.app.speech.api.EngineType
import com.sprich.app.speech.api.Language
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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
    }

    val onboardingDone: Flow<Boolean> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_ONBOARDING_DONE] ?: false }
    val instantMode: Flow<Boolean> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_INSTANT_MODE] ?: false }
    val language: Flow<Language> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{
        when(it[KEY_LANGUAGE]){
            "en" -> Language.EN; "de" -> Language.DE; "es" -> Language.ES; else -> Language.AUTO
        }
    }
    val engineType: Flow<EngineType> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{
        when(it[KEY_ENGINE]){
            "accurate" -> EngineType.ACCURATE; "streaming" -> EngineType.STREAMING; else -> EngineType.FAST
        }
    }
    val haptics: Flow<Boolean> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_HAPTICS] ?: true }
    val commands: Flow<Boolean> = context.ds.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map{ it[KEY_COMMANDS] ?: true }

    suspend fun setOnboardingDone(v: Boolean){ context.ds.edit{it[KEY_ONBOARDING_DONE]=v} }
    suspend fun setInstantMode(v: Boolean){ context.ds.edit{it[KEY_INSTANT_MODE]=v} }
    suspend fun setLanguage(v: Language){ context.ds.edit{it[KEY_LANGUAGE]=v.code} }
    suspend fun setEngine(v: EngineType){ context.ds.edit{it[KEY_ENGINE]= when(v){ EngineType.FAST->"fast"; EngineType.ACCURATE->"accurate"; EngineType.STREAMING->"streaming" }}}
    suspend fun setHaptics(v: Boolean){ context.ds.edit{it[KEY_HAPTICS]=v} }
    suspend fun setCommands(v: Boolean){ context.ds.edit{it[KEY_COMMANDS]=v} }
    suspend fun clearAll(){ context.ds.edit{it.clear()}}
}
