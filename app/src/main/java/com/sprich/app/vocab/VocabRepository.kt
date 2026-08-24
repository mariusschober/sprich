package com.sprich.app.vocab

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.sprich.app.storage.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

@Serializable data class VocabJson(val entries: List<VocabEntryJson>)
@Serializable data class VocabEntryJson(val spoken: String, val written: String)

class VocabRepository(context: Context, private val prefs: Preferences) {
    private val appContext = context.applicationContext
    private val store = PersonalVocabStore()

    fun entries(): List<VocabEntry> = store.all()

    fun apply(text: String): String = store.apply(text)
    fun store(): PersonalVocabStore = store

    suspend fun add(spoken: String, written: String) {
        store.add(spoken, written)
        persist()
    }
    suspend fun remove(spoken: String) { store.remove(spoken); persist() }
    suspend fun clear() { store.clear(); persist() }

    private suspend fun persist() {
        val json = Json.encodeToString(VocabJson(store.all().map{ VocabEntryJson(it.spoken, it.written)}))
        appContext.getSharedPreferences("sprich_vocab", Context.MODE_PRIVATE).edit().putString("json", json).apply()
    }

    suspend fun load() {
        val json = appContext.getSharedPreferences("sprich_vocab", Context.MODE_PRIVATE).getString("json", null) ?: return
        try {
            val parsed = Json.decodeFromString<VocabJson>(json)
            store.clear()
            parsed.entries.forEach{ store.add(it.spoken, it.written)}
        } catch (_: Exception){}
    }

    fun vocabFlow(): Flow<List<VocabEntry>> = kotlinx.coroutines.flow.flow { emit(entries()) }
}
