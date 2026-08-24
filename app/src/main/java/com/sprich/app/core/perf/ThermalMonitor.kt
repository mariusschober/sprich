package com.sprich.app.core.perf

import android.os.Handler
import android.os.Looper
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Polls thermal zone temps and detects throttling.
 * Budget phones throttle quickly; we monitor sustained RTF degradation.
 */
class ThermalMonitor(
    private val onThrottle: (Float) -> Unit = {}
) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)

    fun start() {
        if (running) return
        running = true
        handler.post(check)
    }
    fun stop() { running = false; handler.removeCallbacks(check) }

    private val check = object : Runnable {
        override fun run() {
            if (!running) return
            scope.launch {
                val temp = readTempC()
                if (temp != null && temp > 42.5f) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onThrottle(temp) }
                }
            }
            handler.postDelayed(this, 5000)
        }
    }

    fun readTempC(): Float? {
        return try {
            // Try common zone
            val f = File("/sys/class/thermal/thermal_zone0/temp")
            if (!f.exists()) return null
            val raw = f.readText().trim().toFloatOrNull() ?: return null
            // raw is millidegree or degree?
            if (raw > 1000) raw / 1000 else raw
        } catch (_: Exception) { null }
    }
}
