package com.sprich.app.diagnostics

import android.content.Context
import android.os.Build
import java.io.File

object Diagnostics {
    fun collect(context: Context, engineId: String, rtf: Double? = null): String {
        return buildString {
            appendLine("Sprich diagnostics (local only, no transcript)")
            appendLine("timestamp=${System.currentTimeMillis()}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
            appendLine("engine=$engineId")
            if (rtf != null) appendLine("rtf=$rtf")
            appendLine("availableMem=${Runtime.getRuntime().freeMemory()}")
            appendLine("totalMem=${Runtime.getRuntime().totalMemory()}")
        }
    }

    fun write(context: Context, text: String): File {
        val f = File(context.filesDir, "diagnostics/latest.log")
        f.parentFile?.mkdirs()
        f.writeText(text)
        return f
    }
}
