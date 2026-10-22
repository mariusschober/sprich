package com.sprich.app

import android.content.Context
import android.content.res.XmlResourceParser
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DebugNetworkConfigTest {
    @Test fun debugCleartextIsLimitedToLocalDevelopmentHosts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parser: XmlResourceParser = context.resources.getXml(R.xml.network_security_config)
        var baseCleartext: String? = null
        var localCleartext: String? = null
        val localHosts = mutableSetOf<String>()
        var inDomainConfig = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "base-config" -> baseCleartext = parser.getAttributeValue(null, "cleartextTrafficPermitted")
                "domain-config" -> {
                    inDomainConfig = true
                    localCleartext = parser.getAttributeValue(null, "cleartextTrafficPermitted")
                }
                "domain" -> if (inDomainConfig) parser.nextText().let(localHosts::add)
            }
            if (parser.name == "domain-config" && parser.eventType == XmlPullParser.END_TAG) inDomainConfig = false
        }
        assertEquals("false", baseCleartext)
        assertEquals("true", localCleartext)
        assertEquals(setOf("localhost", "127.0.0.1", "10.0.2.2"), localHosts)
        assertFalse(localHosts.contains("example.com"))
        assertTrue(localHosts.isNotEmpty())
    }
}
