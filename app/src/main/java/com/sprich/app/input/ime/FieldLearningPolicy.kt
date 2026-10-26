package com.sprich.app.input.ime

import android.view.inputmethod.EditorInfo

/**
 * Field-scoped privacy policy for instant start and vocabulary hints.
 *
 * `IME_FLAG_NO_PERSONALIZED_LEARNING` is the host app's explicit request that the input method
 * not learn from what is typed in this field. Recording there without a fresh user action, and
 * attaching saved personal vocabulary to the request, would exceed that request. The flag is
 * checked in addition to the password/PIN gate; an explicit tap on the microphone still starts
 * dictation, because that is a new user decision rather than automatic exposure.
 */
internal object FieldLearningPolicy {

    /** True when opening the keyboard may begin capture without a further user action. */
    fun allowsInstantStart(imeOptions: Int): Boolean =
        (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0

    /**
     * True when saved vocabulary may be attached as recognition hints. The same field signal
     * that blocks nearby editor text also blocks personal vocabulary, independently of the
     * global "help recognize my vocabulary" preference.
     */
    fun allowsVocabularyHints(fieldAllowsContext: Boolean): Boolean = fieldAllowsContext
}
