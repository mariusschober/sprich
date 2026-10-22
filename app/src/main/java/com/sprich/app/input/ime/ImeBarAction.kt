package com.sprich.app.input.ime

import com.sprich.app.R

/** A dead-end setup/permission/API status must open Sprich instead of retrying the same failure. */
internal object ImeBarAction {
    private val setupStatuses = setOf(
        R.string.ime_setup,
        R.string.ime_mic_permission,
        R.string.ime_model_failed,
        R.string.ime_api_config_failure,
        R.string.api_key_missing,
    )

    fun opensSetup(status: Int): Boolean = status in setupStatuses
}
