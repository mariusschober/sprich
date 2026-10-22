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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object SharedVocabStore {
    val store = PersonalVocabStore()
    private val changesMutable = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes: SharedFlow<Unit> = changesMutable
    internal fun notifyChanged() { changesMutable.tryEmit(Unit) }
}

/** A readable copy of an unreadable vocabulary file, kept before a new list is published. */
data class VocabularyCorruption(val preservedFileName: String?, val detectedAtMillis: Long)

@Serializable internal data class VocabExport(
    val version: Int = 1,
    val entries: List<VocabEntryJson> = emptyList(),
    val learned: List<LearnedWord> = emptyList(),
)

data class ImportSummary(val imported: Int, val skipped: Int)

/** Disk commits and memory publication are ordered under one process-wide mutex, off Main. */
class VocabRepository(context: Context, @Suppress("UNUSED_PARAMETER") prefs: Preferences) {
    private val context = context.applicationContext
    private fun file() = AtomicFile(File(context.noBackupFilesDir, "vocabulary.json"))
    private val store get() = SharedVocabStore.store
    @Volatile private var corruption: VocabularyCorruption? = null
    fun entries() = store.all()
    fun learnedWords() = store.learnedWords()
    fun document() = store.document()
    fun apply(text: String) = store.apply(text)
    fun store() = store
    fun changes() = SharedVocabStore.changes
    fun corruption(): VocabularyCorruption? = corruption
    fun acknowledgeCorruption() { corruption = null }
    suspend fun add(spoken: String, written: String) = change { it.addManual(spoken, written) }
    suspend fun addLearned(word: LearnedWord) = change { it.addWord(word) }
    suspend fun removeLearned(id: String) = change { it.copy(learned = it.learned.filterNot { word -> word.id == id }) }
    suspend fun remove(spoken: String) = change { it.copy(entries = it.entries.filterNot { entry -> VocabularyText.key(entry.spoken) == VocabularyText.key(spoken) }) }
    suspend fun clear() = change { VocabJson() }

    /** Versioned local JSON: spellings and already-saved lessons only, never audio. */
    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val document = loadLocked()
            Json { prettyPrint = true; encodeDefaults = true }
                .encodeToString(VocabExport(1, document.entries, document.learned))
        }
    }

    /**
     * Merge a versioned export, validating size, schema, limits and conflicts first. Invalid
     * entries are skipped; the existing dictionary is never cleared as part of an import.
     */
    suspend fun importJson(text: String): ImportSummary {
        require(text.toByteArray(Charsets.UTF_8).size <= 1_000_000) { "Vocabulary import is too large" }
        val export = try {
            Json { ignoreUnknownKeys = true }.decodeFromString<VocabExport>(text)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid vocabulary export")
        }
        require(export.version == 1) { "Unsupported vocabulary version" }
        val manual = export.entries.take(WordLesson.MAX_WORDS).filter {
            VocabularyText.validManual(it.spoken, 128) && VocabularyText.validManual(it.written, 256)
        }.distinctBy { VocabularyText.key(it.spoken) }
        val lessons = export.learned.take(WordLesson.MAX_WORDS).filter { it.isValid() }
        var imported = 0
        var skipped = (export.entries.size - manual.size) + (export.learned.size - lessons.size)
        change { current ->
            var working = current
            for (entry in manual) {
                val existing = working.entries.firstOrNull { VocabularyText.key(it.spoken) == VocabularyText.key(entry.spoken) }
                if (existing != null && VocabularyText.clean(existing.written) != VocabularyText.clean(entry.written)) {
                    skipped++
                    continue
                }
                val result = runCatching { working.addManual(entry.spoken, entry.written) }
                result.onSuccess { working = it; imported++ }.onFailure { skipped++ }
            }
            for (lesson in lessons) {
                val result = runCatching { working.addWord(lesson) }
                result.onSuccess { working = it; imported++ }.onFailure { skipped++ }
            }
            working
        }
        return ImportSummary(imported, skipped)
    }

    private suspend fun change(transform: (VocabJson) -> VocabJson) = withContext(Dispatchers.IO) {
        lock.withLock {
            val next = transform(loadLocked())
            // An unreadable file that could not be copied must not be silently overwritten.
            if (corruption?.preservedFileName == null && corruption != null) {
                throw IllegalStateException("Corrupt vocabulary could not be preserved")
            }
            val json = Json.encodeToString(next)
            val destination = file()
            val output = destination.startWrite()
            try { output.write(json.toByteArray(Charsets.UTF_8)); output.fd.sync(); destination.finishWrite(output) }
            catch (error: Exception) { destination.failWrite(output); throw error }
            corruption = null
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
        val json = try {
            if (!hasFile) context.getSharedPreferences("sprich_vocab", Context.MODE_PRIVATE).getString("json", null)
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
        } catch (_: Exception) {
            return quarantineLocked(source)
        }
        val document = try {
            if (json == null) {
                // Preserve an earlier corruption notice until the user saves a new list.
                if (corruption == null) VocabJson() else store.document()
            } else if (json.length > 1_000_000) {
                throw IllegalArgumentException("Vocabulary file is too large")
            } else {
                val decoded = Json.decodeFromString<VocabJson>(json)
                val manual = decoded.entries.take(WordLesson.MAX_WORDS).filter {
                    VocabularyText.validManual(it.spoken, 128) && VocabularyText.validManual(it.written, 256)
                }.distinctBy { VocabularyText.key(it.spoken) }
                // Invalid/conflicting lessons fail closed; legacy manual rules remain usable.
                decoded.learned.take(WordLesson.MAX_WORDS - manual.size).fold(VocabJson(manual)) { valid, lesson ->
                    runCatching { valid.addWord(lesson) }.getOrDefault(valid)
                }
            }
        } catch (_: Exception) {
            return quarantineLocked(source)
        }
        if (json != null) corruption = null
        publish(document)
        return document
    }

    /** Keep the exact unreadable bytes for offline recovery, then publish a safe empty document. */
    private fun quarantineLocked(source: AtomicFile): VocabJson {
        val base = source.baseFile
        val backup = File(base.path + ".bak")
        val payload = when {
            base.isFile -> base
            backup.isFile -> backup
            else -> null
        }
        val preserved = try {
            payload?.let {
                val parent = base.parentFile ?: return@let null
                var target = File(parent, "vocabulary.corrupt.${System.currentTimeMillis()}.json")
                var suffix = 1
                while (target.exists()) {
                    target = File(parent, "vocabulary.corrupt.${System.currentTimeMillis()}-${suffix++}.json")
                }
                it.copyTo(target, overwrite = false)
                source.delete()
                target.name
            }
        } catch (_: Exception) { null }
        corruption = VocabularyCorruption(preserved, System.currentTimeMillis())
        val empty = VocabJson()
        publish(empty)
        return empty
    }

    private fun publish(document: VocabJson) = store.replace(document.entries.map { VocabEntry(it.spoken, it.written) }, document.learned)
    fun vocabFlow(): Flow<List<VocabEntry>> = kotlinx.coroutines.flow.flow { load(); emit(entries()) }
    companion object { private val lock = Mutex() }
}
