package com.sprich.app

import android.content.res.XmlResourceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T2 declaration guard: the shipped `method.xml` must offer the four typing subtypes *and* one
 * one-shot voice subtype that AOSP-lineage keyboards can discover through
 * getShortcutInputMethodsAndSubtypes(). RecognitionService / ACTION_RECOGNIZE_SPEECH are
 * deliberately not declared (design note only), so this test pins the handoff surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceSubtypeDeclarationTest {

    private fun subtypes(): List<Map<String, String?>> {
        val parser: XmlResourceParser = RuntimeEnvironment.getApplication().resources.getXml(R.xml.method)
        val android = "http://schemas.android.com/apk/res/android"
        val attributes = listOf("imeSubtypeMode", "imeSubtypeLocale", "label", "isAuxiliary", "overridesImplicitlyEnabledSubtype")
        val found = mutableListOf<Map<String, String?>>()
        try {
            var event = parser.eventType
            while (event != XmlResourceParser.END_DOCUMENT) {
                if (event == XmlResourceParser.START_TAG && parser.name == "subtype") {
                    found += attributes.associateWith { parser.getAttributeValue(android, it) }
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return found
    }

    @Test
    fun keyboardSubtypesAreRetained() {
        val keyboard = subtypes().filter { it["imeSubtypeMode"] == "keyboard" }
        assertEquals("four typing subtypes must remain declared", 4, keyboard.size)
        assertEquals(setOf("en", "de", "es", "fr"), keyboard.map { it["imeSubtypeLocale"] }.toSet())
    }

    @Test
    fun typingSubtypeIsDeclaredFirstSoDefaultSelectionStaysTyping() {
        // The framework's shortcut-voice lookup indexes the original subtype array with the
        // position of the enabled subtype. The typing subtype must be first so selecting Sprich
        // normally lands on a typing subtype, not the one-shot voice subtype.
        assertEquals("keyboard", subtypes().first()["imeSubtypeMode"])
        assertEquals("true", subtypes().first()["overridesImplicitlyEnabledSubtype"])
    }

    @Test
    fun oneAuxiliaryVoiceSubtypeIsDeclared() {
        val voice = subtypes().filter { it["imeSubtypeMode"] == "voice" }
        assertEquals("exactly one voice subtype", 1, voice.size)
        val declaration = voice.single()
        assertEquals("true", declaration["isAuxiliary"])
        assertEquals("true", declaration["overridesImplicitlyEnabledSubtype"])
        assertNotNull("voice subtype needs a label", declaration["label"])
    }
}
