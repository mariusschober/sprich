package com.sprich.app.input.ime

import com.sprich.app.R

/**
 * Why a dictation start stopped before capture. API configuration and credential failures
 * are not missing speech files and must not send the user to the model download screen.
 */
internal enum class StartFailureKind { LOCAL_MODEL, API_CONFIG, API_KEY }

internal data class StartFailureCopy(val status: Int, val hint: Int)

internal object StartFailureText {
    fun remote(remoteConfigured: Boolean, credentialPresent: Boolean): StartFailureKind? = when {
        !remoteConfigured -> StartFailureKind.API_CONFIG
        !credentialPresent -> StartFailureKind.API_KEY
        else -> null
    }

    fun copy(kind: StartFailureKind): StartFailureCopy = when (kind) {
        StartFailureKind.LOCAL_MODEL -> StartFailureCopy(R.string.ime_model_failed, R.string.ime_model_failed_hint)
        StartFailureKind.API_CONFIG -> StartFailureCopy(R.string.ime_api_config_failure, R.string.api_check_key)
        StartFailureKind.API_KEY -> StartFailureCopy(R.string.api_key_missing, R.string.api_check_key)
    }
}
