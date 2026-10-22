package com.sprich.app

import android.os.Looper
import com.sprich.app.ui.settings.runClearData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ClearDataTest {
    @Test fun clearRunsOffTheMainThread() = runBlocking {
        var onMain: Boolean? = null
        val ok = runClearData {
            onMain = Looper.myLooper() == Looper.getMainLooper()
            true
        }
        assertTrue(ok)
        assertFalse(onMain!!)
    }

    @Test fun exceptionBecomesAReportedFailure() = runBlocking {
        val ok = runClearData { throw IllegalStateException("system refused") }
        assertFalse(ok)
    }
}
