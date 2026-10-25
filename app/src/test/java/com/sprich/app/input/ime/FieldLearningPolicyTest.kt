package com.sprich.app.input.ime

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldLearningPolicyTest {

    private val noPersonalizedLearning = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

    @Test
    fun ordinaryFieldAllowsInstantStart() {
        assertTrue(FieldLearningPolicy.allowsInstantStart(0))
        assertTrue(FieldLearningPolicy.allowsInstantStart(EditorInfo.IME_FLAG_NO_FULLSCREEN))
    }

    @Test
    fun optedOutFieldBlocksInstantStart() {
        assertFalse(FieldLearningPolicy.allowsInstantStart(noPersonalizedLearning))
        assertFalse(FieldLearningPolicy.allowsInstantStart(noPersonalizedLearning or EditorInfo.IME_FLAG_NO_EXTRACT_UI))
    }

    @Test
    fun fieldOptOutBlocksVocabularyHints() {
        assertFalse(FieldLearningPolicy.allowsVocabularyHints(fieldAllowsContext = false))
    }

    @Test
    fun ordinaryFieldKeepsVocabularyHintsAvailable() {
        assertTrue(FieldLearningPolicy.allowsVocabularyHints(fieldAllowsContext = true))
    }
}
