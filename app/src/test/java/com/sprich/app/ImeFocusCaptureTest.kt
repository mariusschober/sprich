package com.sprich.app

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.sprich.app.core.audio.AudioCapture
import com.sprich.app.input.ime.SprichIME
import com.sprich.app.input.lifecycle.DictationSession
import com.sprich.app.input.lifecycle.SessionState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeFocusCaptureTest {
    @Test fun focusAndInputRestartDoNotPretendToStartTheMicrophone() {
        val service = Robolectric.buildService(SprichIME::class.java).create()
        val ime = service.get()
        val session = SprichIME::class.java.getDeclaredField("session").apply { isAccessible = true }.get(ime) as DictationSession
        val audio = SprichIME::class.java.getDeclaredField("audio").apply { isAccessible = true }.get(ime) as AudioCapture
        try {
            for (restarting in listOf(false, true)) {
                ime.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; initialSelStart = 0; initialSelEnd = 0 }, restarting)
                assertSame("Focus alone must show the idle tap action", SessionState.Idle, session.state.value)
                assertFalse(session.isActive)
                assertFalse(audio.isActive())
            }
        } finally { service.destroy() }
    }
}
