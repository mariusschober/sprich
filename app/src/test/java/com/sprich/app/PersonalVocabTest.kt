package com.sprich.app

import com.sprich.app.vocab.PersonalVocabStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalVocabTest {

    @Test
    fun appliesWrittenFormCaseInsensitively() {
        val store = PersonalVocabStore()
        store.add("marius", "Marius K.")
        assertEquals("hey Marius K.!", store.apply("hey MARIUS!"))
    }

    @Test
    fun longerEntryWinsOverSubstring() {
        val store = PersonalVocabStore()
        store.add("york", "YORK")
        store.add("new york", "New York City")
        assertEquals("I love New York City", store.apply("I love new york"))
    }

    @Test
    fun wordBoundaryOnly() {
        val store = PersonalVocabStore()
        store.add("kai", "Kai U.")
        // "kaiser" contains "kai" but must not be replaced (word boundary)
        assertEquals("the kaiser speaks", store.apply("the kaiser speaks"))
        assertEquals("hi Kai U.", store.apply("hi kai"))
    }

    @Test
    fun removeDropsEntry() {
        val store = PersonalVocabStore()
        store.add("acme", "ACME Corp")
        store.remove("acme")
        assertEquals("call acme now", store.apply("call acme now"))
    }

    @Test
    fun emptyTextPassthrough() {
        val store = PersonalVocabStore()
        store.add("x", "Y")
        assertEquals("", store.apply(""))
    }

    @Test
    fun multipleEntriesApplied() {
        val store = PersonalVocabStore()
        store.add("sprich", "Sprich")
        store.add("onnx", "ONNX")
        val out = store.apply("sprich uses onnx today")
        assertTrue(out.contains("Sprich") && out.contains("ONNX"))
    }
}
