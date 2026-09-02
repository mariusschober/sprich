package com.sprich.app.diagnostics

import android.content.Context
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build
import java.io.File

object Diagnostics {
    fun collect(context: Context, engineId: String, rtf: Double? = null, languageTag: String? = null, task: String? = null, sessionId: Long? = null): String {
        return buildString {
            appendLine("Sprich diagnostics (local only, no transcript, no raw audio)")
            appendLine("timestamp=${System.currentTimeMillis()}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
            appendLine("engine=$engineId")
            if (languageTag != null) appendLine("resolvedLanguage=$languageTag")
            if (task != null) appendLine("task=$task")
            if (sessionId != null) appendLine("sessionId=$sessionId")
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

    /** Persists only numeric exit metadata; never copy an OS trace or process description. */
    fun capturePreviousExit(context: Context): File? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exit = manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .maxByOrNull(ApplicationExitInfo::getTimestamp)
                ?: return null
            val file = File(context.filesDir, "diagnostics/last-exit.log")
            file.parentFile?.mkdirs()
            file.writeText(
                buildString {
                    appendLine("timestamp=${exit.timestamp}")
                    appendLine("reason=${exit.reason}")
                    appendLine("status=${exit.status}")
                    appendLine("importance=${exit.importance}")

                }
            )
            file
        } catch (_: Exception) {
            null
        }
    }
}
