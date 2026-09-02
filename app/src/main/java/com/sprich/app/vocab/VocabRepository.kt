package com.sprich.app.vocab

import android.content.Context
import com.sprich.app.storage.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class VocabJson(val entries: List<VocabEntryJson>)
@Serializable data class VocabEntryJson(val spoken: String, val written: String)

object SharedVocabStore {
    val store = PersonalVocabStore()
    private val changesMutable = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes: SharedFlow<Unit> = changesMutable
    internal fun notifyChanged() { changesMutable.tryEmit(Unit) }
}

/** Disk commits and memory publication are ordered under one process-wide mutex, off Main. */
class VocabRepository(context: Context, @Suppress("UNUSED_PARAMETER") prefs: Preferences) {
    private val context = context.applicationContext
    private val store get() = SharedVocabStore.store
    fun entries() = store.all()
    fun apply(text: String) = store.apply(text)
    fun store() = store
    fun changes() = SharedVocabStore.changes
    suspend fun add(spoken: String, written: String) = change { entries ->
        require(spoken.isNotBlank() && written.isNotBlank() && spoken.length <= 128 && written.length <= 256)
        val next = entries.filterNot { it.spoken.equals(spoken.trim(), true) } + VocabEntry(spoken.trim(), written.trim())
        require(next.size <= 200)
        next
    }
    suspend fun remove(spoken: String) = change { entries -> entries.filterNot { it.spoken.equals(spoken, true) } }
    suspend fun clear() = change { emptyList() }
    private suspend fun change(transform: (List<VocabEntry>) -> List<VocabEntry>) = withContext(Dispatchers.IO) {
        lock.withLock {
            loadLocked()
            val next = transform(store.all())
            val json = Json.encodeToString(VocabJson(next.map { VocabEntryJson(it.spoken, it.written) }))
            check(context.getSharedPreferences("sprich_vocab", Context.MODE_PRIVATE).edit().putString("json", json).commit())
            store.replace(next)
            SharedVocabStore.notifyChanged()
        }
    }
    suspend fun load() = withContext(Dispatchers.IO) { lock.withLock { loadLocked() } }
    private fun loadLocked() {
        val json = context.getSharedPreferences("sprich_vocab", Context.MODE_PRIVATE).getString("json", null)
        val entries = try {
            if (json == null || json.length > 120_000) emptyList()
            else Json.decodeFromString<VocabJson>(json).entries.take(200).filter { it.spoken.isNotBlank() && it.spoken.length <= 128 && it.written.isNotBlank() && it.written.length <= 256 }.map { VocabEntry(it.spoken, it.written) }
        } catch (_: Exception) { emptyList() }
        store.replace(entries)
    }
    fun vocabFlow(): Flow<List<VocabEntry>> = kotlinx.coroutines.flow.flow { load(); emit(entries()) }
    companion object { private val lock = Mutex() }
}
