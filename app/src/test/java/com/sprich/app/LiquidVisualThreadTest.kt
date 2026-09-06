package com.sprich.app

import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.sprich.app.input.ime.SprichIME
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiquidVisualThreadTest {

    private fun setDotView(ime: SprichIME, v: View) {
        val f = SprichIME::class.java.getDeclaredField("dotView")
        f.isAccessible = true
        f.set(ime, v)
    }

    private fun updateLiquidVisual(ime: SprichIME, rms: Float) {
        val m = SprichIME::class.java.getDeclaredMethod("updateLiquidVisual", Float::class.java)
        m.isAccessible = true
        m.invoke(ime, rms)
    }

    @Test
    fun backgroundCallMarshalsToMainThread() {
        val ime = Robolectric.buildService(SprichIME::class.java).create().get()
        val dot = View(ApplicationProvider.getApplicationContext())
        assertEquals(1.0f, dot.scaleX)
        setDotView(ime, dot)

        // Simulate the audio-callback thread calling in (rms=0.05 -> scaleX target 2.0).
        val t = Thread { updateLiquidVisual(ime, 0.05f) }
        t.start()
        t.join(5000)
        assertFalse(t.isAlive)

        // Must NOT have mutated the view off-main: still untouched until the main looper runs.
        assertEquals(1.0f, dot.scaleX)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2.0f, dot.scaleX)
    }

    @Test
    fun mainThreadCallAppliesSynchronously() {
        val ime = Robolectric.buildService(SprichIME::class.java).create().get()
        val dot = View(ApplicationProvider.getApplicationContext())
        setDotView(ime, dot)
        updateLiquidVisual(ime, 0.05f)
        assertEquals(2.0f, dot.scaleX)
    }
}
