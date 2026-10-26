package com.sprich.app

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T2 device evidence: what the framework actually parses and exposes for Sprich's subtypes.
 * The input-method picker hides auxiliary subtypes by design, so handoff discovery must be
 * checked through getShortcutInputMethodsAndSubtypes() and the enabled subtype list.
 */
@RunWith(AndroidJUnit4::class)
class SubtypeDiscoveryDeviceTest {

    @Test
    fun frameworkExposesVoiceSubtypeForKeyboardHandoff() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val me = ctx.packageName
        val imi = imm.enabledInputMethodList.firstOrNull { it.packageName == me }
        if (imi != null) {
            android.util.Log.i("SubtypeDiscovery", "parsed subtypeCount=${imi.subtypeCount}")
            for (index in 0 until imi.subtypeCount) {
                val subtype = imi.getSubtypeAt(index)
                android.util.Log.i(
                    "SubtypeDiscovery",
                    "parsed[$index] mode=${subtype.mode} locale=${subtype.locale} " +
                        "auxiliary=${subtype.isAuxiliary} overridesImplicit=${subtype.overridesImplicitlyEnabledSubtype()}",
                )
            }
            val enabled = imm.getEnabledInputMethodSubtypeList(imi, true)
            android.util.Log.i(
                "SubtypeDiscovery",
                "enabledSubtypes(forImplicit=${true})=${enabled.map { it.mode }}",
            )
        } else {
            android.util.Log.w("SubtypeDiscovery", "Sprich debug IME not in enabled list")
        }
        val shortcuts = imm.shortcutInputMethodsAndSubtypes
        for ((info, subtypes) in shortcuts) {
            android.util.Log.i("SubtypeDiscovery", "shortcut IME=${info.packageName} modes=${subtypes.map { it.mode }}")
        }
        android.util.Log.i("SubtypeDiscovery", "shortcutMap=" + shortcuts.entries.joinToString { (k, v) -> "${k.packageName}:" + v.joinToString(",") { it.mode } })
        val mine = shortcuts.entries.filter { it.key.packageName == me }.flatMap { it.value }
        assertTrue("voice subtype must be exposed for keyboard handoff", mine.any { it.mode == "voice" })
    }
}
