package com.sprich.app.ui.vocab

import java.io.InputStream

internal const val MAX_VOCAB_IMPORT_BYTES = 1_000_000

/** Read at most one import body; never trust the provider's advertised size. */
internal fun readVocabularyImport(input: InputStream, maxBytes: Int = MAX_VOCAB_IMPORT_BYTES): String {
    val buffer = ByteArray(maxBytes + 1)
    var size = 0
    while (size < buffer.size) {
        val read = input.read(buffer, size, buffer.size - size)
        if (read < 0) break
        size += read
    }
    require(size <= maxBytes) { "Vocabulary import is too large" }
    return String(buffer, 0, size, Charsets.UTF_8)
}
