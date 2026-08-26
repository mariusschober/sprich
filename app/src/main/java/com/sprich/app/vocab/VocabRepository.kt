package com.sprich.app.vocab

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.sprich.app.storage.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

@Serializable data class VocabJson(val entries: List<VocabEntryJson>)
@Serializable data class VocabEntryJson(val spoken: String, val written: String)

/**
 * Process-wide vocabulary store. The IME, the settings UI and any future consumer
 * share ONE in-memory instance, so an entry added in Settings is live in the keyboard
 * immediately — no service restart, no stale copies.
 */
object SharedVocabStore {
    val store = PersonalVocabStore()
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes: SharedFlow<Unit> = _changes

    internal fun notifyChanged() { _changes.tryEmit(Unit) }
}

class VocabRepository(context: Context, private val prefs: Preferences) {
    private val appContext = context.applicationContext
    private val store get() = SharedVocabStore.store

    fun entries(): List<VocabEntry> = store.all()

    fun apply(text: String): String = store.apply(text)
    fun store(): PersonalVocabStore = store
    fun changes(): SharedFlow<Unit> = SharedVocabStore.changes

    suspend fun add(spoken: String, written: String) {
        store.add(spoken, written)
        persist()
        SharedVocabStore.notifyChanged()
    }
    suspend fun remove(spoken: String) { store.remove(spoken); persist(); SharedVocabStore.notifyChanged() }
    suspend fun clear() { store.clear(); persist(); SharedVocabStore.notifyChanged() }

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
