package com.sprich.app

import android.os.Looper
import com.sprich.app.input.ime.MainThreadVisualDispatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainThreadVisualDispatcherTest {
    @Test fun audioThreadWorkRunsOnMain() {
        val done = CountDownLatch(1)
        val wrongThread = AtomicBoolean(false)
        val poster = Thread {
            MainThreadVisualDispatcher.post {
                if (Looper.myLooper() != Looper.getMainLooper()) wrongThread.set(true)
                done.countDown()
            }
        }
        poster.start()
        poster.join()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertFalse(wrongThread.get())
    }

    @Test fun mainThreadWorkRunsInline() {
        val ran = AtomicBoolean(false)
        MainThreadVisualDispatcher.post { ran.set(true) }
        assertTrue(ran.get())
    }
}
