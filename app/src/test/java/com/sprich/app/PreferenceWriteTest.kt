package com.sprich.app

import com.sprich.app.ui.persistPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceWriteTest {
    @Test fun successfulWriteReturnsTrue() = runBlocking {
        assertTrue(persistPreference({ }) { })
    }

    @Test fun failedWriteIsReportedWithoutCrashing() = runBlocking {
        var reported = false
        val ok = persistPreference({ reported = true }) { throw java.io.IOException("disk full") }
        assertFalse(ok)
        assertTrue(reported)
    }

    @Test fun cancellationIsNotSwallowed() = runBlocking {
        var cancelled = false
        try {
            persistPreference({ }) { throw CancellationException("cancelled") }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }
}
