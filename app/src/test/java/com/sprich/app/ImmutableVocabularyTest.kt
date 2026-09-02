package com.sprich.app

import com.sprich.app.vocab.PersonalVocabStore
import com.sprich.app.input.commands.SpokenEditingParser
import com.sprich.app.speech.api.Language
import org.junit.Assert.*
import org.junit.Test

class ImmutableVocabularyTest {
    @Test fun updatesAndClearCannotChangeCapturedUtteranceRules() {
        val store = PersonalVocabStore()
        store.add("sprick", "Sprich")
        val captured = store.snapshot()
        store.add("sprick", "Other")
        store.clear()
        assertEquals("Sprich", captured.apply("sprick"))
        assertEquals("sprick", store.apply("sprick"))
    }
    @Test fun replacementIsAppliedOnce() {
        val store = PersonalVocabStore()
        store.add("alpha", "beta"); store.add("beta", "gamma")
        assertEquals("beta", store.snapshot().apply("alpha"))
    }
    @Test fun frenchCommandsDoNotUseEnglishTable() {
        assertEquals("\n", SpokenEditingParser.parse("nouvelle ligne", Language.FR, true).text)
        assertEquals("__DELETE_LAST__", SpokenEditingParser.parse("efface ça.", Language.FR, true).text)
        assertEquals("delete that", SpokenEditingParser.parse("delete that", Language.FR, true).text)
        assertEquals("nouvelle ligne", SpokenEditingParser.parse("nouvelle ligne", Language.FR, false).text)
    }
}
