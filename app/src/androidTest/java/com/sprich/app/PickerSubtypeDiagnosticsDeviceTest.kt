package com.sprich.app

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R3: explains the system input-method picker rows for Sprich (blank subtype rows / manual
 * refresh). Prints, for the release and debug IMEs: parsed subtypes, display names as the
 * framework resolves them, enabled subtype lists (explicit vs implicit) and the shortcut-voice
 * map. Writes files/bakeoff/picker-diagnostics.json for the host side.
 */
@RunWith(AndroidJUnit4::class)
class PickerSubtypeDiagnosticsDeviceTest {

    private fun app() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SprichApp

    @Test
    fun pickerSubtypeDiagnostics() {
        val ctx = app()
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val pm = ctx.packageManager
        val out = JSONArray()
        for (packageName in listOf("com.sprich.app", "com.sprich.app.debug")) {
            val imi = try { imm.inputMethodList.firstOrNull { it.packageName == packageName } } catch (_: Exception) { null }
            if (imi == null) {
                out.put(JSONObject().put("package", packageName).put("present", false))
                continue
            }
            val subtypesArray = JSONArray()
            for (i in 0 until imi.subtypeCount) {
                val subtype = imi.getSubtypeAt(i)
                val display = try {
                    subtype.getDisplayName(ctx, imi.packageName, imi.serviceInfo.applicationInfo)
                } catch (e: Exception) { "ERR:${e.javaClass.simpleName}" }
                subtypesArray.put(JSONObject()
                    .put("index", i)
                    .put("mode", subtype.mode)
                    .put("locale", subtype.locale)
                    .put("auxiliary", subtype.isAuxiliary)
                    .put("overridesImplicit", subtype.overridesImplicitlyEnabledSubtype())
                    .put("displayName", display)
                    .put("hashCode", subtype.hashCode()))
            }
            val explicit = try { imm.getEnabledInputMethodSubtypeList(imi, false).map { it.mode } } catch (_: Exception) { emptyList() }
            val implicit = try { imm.getEnabledInputMethodSubtypeList(imi, true).map { it.mode } } catch (_: Exception) { emptyList() }
            out.put(JSONObject()
                .put("package", packageName)
                .put("present", true)
                .put("subtypeCount", imi.subtypeCount)
                .put("subtypes", subtypesArray)
                .put("enabledExplicitModes", JSONArray(explicit as List<*>))
                .put("enabledImplicitModes", JSONArray(implicit as List<*>)))
        }
        val shortcuts = imm.shortcutInputMethodsAndSubtypes
        val shortcutArray = JSONArray()
        for ((info, subtypes) in shortcuts) {
            shortcutArray.put(JSONObject().put("package", info.packageName)
                .put("modes", JSONArray(subtypes.map { it.mode } as List<*>)))
        }
        val doc = JSONObject()
            .put("currentIme", android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD))
            .put("imes", out)
            .put("shortcutVoice", shortcutArray)
        val dir = java.io.File(ctx.filesDir, "bakeoff").apply { mkdirs() }
        java.io.File(dir, "picker-diagnostics.json").writeText(doc.toString())
        android.util.Log.i("PickerDiag", doc.toString())
        assertTrue(true)
    }
}
