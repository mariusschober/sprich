package com.sprich.app.input.ime

import com.sprich.app.input.lifecycle.UtteranceToken
import com.sprich.app.speech.ResolvedUtteranceLanguage

/**
 * U03 recovery retry (plan §9.6). When recognition produced non-blank text but
 * nothing was committed (failed insert, abandoned field), the IME keeps this
 * record so the user can explicitly retry the insert. Retry is same-field
 * only, never into password fields, and only with a live input connection —
 * cross-field or stale restores are refused (the Home review card covers
 * reading/discarding instead). The caller additionally rechecks token
 * generation/session freshness exactly like a post-decode commit.
 */
data class FailedCommit(
    val token: UtteranceToken,
    val text: String,
    val resolved: ResolvedUtteranceLanguage,
)

fun retryEligible(
    record: FailedCommit?,
    currentFieldId: String?,
    inputConnectionPresent: Boolean,
    fieldControllerCurrent: Boolean,
    isPasswordField: Boolean,
): Boolean {
    if (record == null || record.text.isBlank()) return false
    if (isPasswordField) return false
    val recordField = record.token.fieldId ?: return false
    if (currentFieldId != recordField) return false
    if (!inputConnectionPresent || !fieldControllerCurrent) return false
    return true
}
