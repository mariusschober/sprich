package com.sprich.app.vocab

import android.content.Context
import android.util.AtomicFile
import com.sprich.app.storage.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object SharedVocabStore {
    val store = PersonalVocabStore()
    private val changesMutable = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes: SharedFlow<Unit> = changesMutable
    internal fun notifyChanged() { changesMutable.tryEmit(Unit) }
}

/** Disk commits and memory publication are ordered under one process-wide mutex, off Main. */
class VocabRepository(context: Context, @Suppress("UNUSED_PARAMETER") prefs: Preferences) {
    private val context = context.applicationContext
    private fun file() = AtomicFile(File(context.noBackupFilesDir, "vocabulary.json"))
    private val store get() = SharedVocabStore.store
    fun entries() = store.all()
    fun learnedWords() = store.learnedWords()
    fun document() = store.document()
    fun apply(text: String) = store.apply(text)
    fun store() = store
    fun changes() = SharedVocabStore.changes
    suspend fun add(spoken: String, written: String) = change { it.addManual(spoken, written) }
    suspend fun addLearned(word: LearnedWord) = change { it.addWord(word) }
    suspend fun removeLearned(id: String) = change { it.copy(learned = it.learned.filterNot { word -> word.id == id }) }
    suspend fun remove(spoken: String) = change { it.copy(entries = it.entries.filterNot { entry -> VocabularyText.key(entry.spoken) == VocabularyText.key(spoken) }) }
    suspend fun clear() = change { VocabJson() }

    private suspend fun change(transform: (VocabJson) -> VocabJson) = withContext(Dispatchers.IO) {
        lock.withLock {
            val next = transform(loadLocked())
            val json = Json.encodeToString(next)
            val destination = file()
            val output = destination.startWrite()
            try { output.write(json.toByteArray(Charsets.UTF_8)); output.fd.sync(); destination.finishWrite(output) }
            catch (error: Exception) { destination.failWrite(output); throw error }
            publish(next)
            // The new file is authoritative, including an explicitly empty dictionary.
            context.getSharedPreferences("sprich_vocab", Context.MODE_PRIVATE).edit().remove("json").commit()
            SharedVocabStore.notifyChanged()
        }
    }
    suspend fun load() = withContext(Dispatchers.IO) { lock.withLock { loadLocked() } }
    private fun loadLocked(): VocabJson {
        val source = file()
        val hasFile = source.baseFile.exists() || File(source.baseFile.path + ".bak").exists()
        val json = if (!hasFile) context.getSharedPreferences("sprich_vocab", Context.MODE_PRIVATE).getString("json", null)
            else source.openRead().use { input ->
                val buffer = ByteArray(1_000_001)
                var size = 0
                while (size < buffer.size) {
                    val count = input.read(buffer, size, buffer.size - size)
                    if (count < 0) break
                    size += count
                }
                check(size <= 1_000_000) { "Vocabulary file is too large" }
                String(buffer, 0, size, Charsets.UTF_8)
            }
        val document = try {
            if (json == null || json.length > 1_000_000) VocabJson()
            else {
                val decoded = Json.decodeFromString<VocabJson>(json)
                val manual = decoded.entries.take(WordLesson.MAX_WORDS).filter {
                    VocabularyText.validManual(it.spoken, 128) && VocabularyText.validManual(it.written, 256)
                }.distinctBy { VocabularyText.key(it.spoken) }
                // Invalid/conflicting lessons fail closed; legacy manual rules remain usable.
                decoded.learned.take(WordLesson.MAX_WORDS - manual.size).fold(VocabJson(manual)) { valid, lesson ->
                    runCatching { valid.addWord(lesson) }.getOrDefault(valid)
                }
            }
        } catch (_: Exception) { VocabJson() }
        publish(document)
        return document
    }
    private fun publish(document: VocabJson) = store.replace(document.entries.map { VocabEntry(it.spoken, it.written) }, document.learned)
    fun vocabFlow(): Flow<List<VocabEntry>> = kotlinx.coroutines.flow.flow { load(); emit(entries()) }
    companion object { private val lock = Mutex() }
}
