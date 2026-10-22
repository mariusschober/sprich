package com.sprich.app

import com.sprich.app.input.ime.fallbackRouteFor
import com.sprich.app.speech.LocalAsrRoute
import com.sprich.app.speech.TranscriptionMode
import com.sprich.app.speech.api.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteReadinessTest {
    private val route = LocalAsrRoute.AccurateCanary(Language.DE)

    @Test fun fallbackUsesOnlyTheSessionStartReadinessSnapshot() {
        assertEquals(route, fallbackRouteFor(TranscriptionMode.API_PRIMARY, true, true, route))
        assertNull(fallbackRouteFor(TranscriptionMode.API_PRIMARY, true, false, route))
        assertNull(fallbackRouteFor(TranscriptionMode.API_PRIMARY, false, true, route))
        assertNull(fallbackRouteFor(TranscriptionMode.ON_DEVICE, true, true, route))
        assertNull(fallbackRouteFor(TranscriptionMode.LOCAL_API_FALLBACK, true, true, route))
    }
}
