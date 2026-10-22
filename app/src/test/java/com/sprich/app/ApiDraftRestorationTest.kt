package com.sprich.app

import androidx.compose.runtime.saveable.SaverScope
import com.sprich.app.speech.remote.VoiceApiOptions
import com.sprich.app.ui.settings.VoiceApiOptionsSaver
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiDraftRestorationTest {
    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    @Test fun voiceOptionsSurviveConfigurationRestoration() {
        val original = VoiceApiOptions(
            streaming = false,
            detectTurns = false,
            speakerLabels = true,
            languageHints = setOf("de", "fr"),
        )
        val saved = with(VoiceApiOptionsSaver) { scope.save(original) }!!
        assertEquals(original, VoiceApiOptionsSaver.restore(saved))
    }
}
