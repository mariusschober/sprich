package com.sprich.app

import com.sprich.app.input.ime.ImeBarAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeBarActionTest {
    @Test fun setupPermissionAndApiDeadEndsOpenSprich() {
        for (status in listOf(
            R.string.ime_setup,
            R.string.ime_mic_permission,
            R.string.ime_model_failed,
            R.string.ime_api_config_failure,
            R.string.api_key_missing,
        )) assertTrue(status.toString(), ImeBarAction.opensSetup(status))
    }

    @Test fun normalDictationStatesStillStartOrStopCapture() {
        for (status in listOf(
            R.string.ime_start,
            R.string.ime_listening,
            R.string.ime_transcribing,
            R.string.ime_loading,
            R.string.ime_no_speech,
            R.string.ime_insert_failed,
        )) assertFalse(status.toString(), ImeBarAction.opensSetup(status))
    }
}
