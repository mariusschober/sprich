package com.sprich.app

import android.content.Context
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.sprich.app.input.ime.VoiceGestureBar
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceGestureBarTest {
    @Test fun aChildTapDoesNotAlsoClickTheVoiceBar() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bar = VoiceGestureBar(context)
        val child = View(context)
        bar.addView(child)
        bar.layout(0, 0, 300, 80)
        child.layout(240, 10, 290, 70)
        bar.tapTargets = listOf(child)
        var barClicks = 0; var childClicks = 0
        bar.setOnClickListener { barClicks++ }
        child.setOnClickListener { childClicks++ }
        bar.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 260f, 40f, 0))
        bar.dispatchTouchEvent(MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, 260f, 40f, 0))
        assertEquals(1, childClicks)
        assertEquals(0, barClicks)
    }

    @Test fun aCenterTapStillClicksTheVoiceBarOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bar = VoiceGestureBar(context).apply { layout(0, 0, 300, 80) }
        var clicks = 0
        bar.setOnClickListener { clicks++ }
        bar.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 100f, 40f, 0))
        bar.dispatchTouchEvent(MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, 100f, 40f, 0))
        assertEquals(1, clicks)
    }
}
