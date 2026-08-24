package com.sprich.app.speech.warmup

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.sprich.app.speech.api.EngineType
import com.sprich.app.speech.api.SpeechEngine
import com.sprich.app.storage.Preferences
import kotlinx.coroutines.*

enum class Warmth { COLD, WARMING, WARM, ACTIVE, UNLOADED }

class ModelWarmthManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile var warmth: Warmth = Warmth.COLD
        private set
    @Volatile var currentEngine: SpeechEngine? = null

    private var lastActiveMs = 0L
    private var warmJob: Job? = null

    fun setEngine(engine: SpeechEngine) {
        // unload previous if different
        val prev = currentEngine
        if (prev != null && prev.engineId != engine.engineId) {
            scope.launch { prev.unload(); Log.i("Warmth", "unloaded ${prev.engineId}") }
        }
        currentEngine = engine
        warmth = Warmth.COLD
    }

    fun preloadIfNeeded() {
        val e = currentEngine ?: return
        if (warmth == Warmth.WARM || warmth == Warmth.ACTIVE || warmth == Warmth.WARMING) return
        warmJob?.cancel()
        warmJob = scope.launch {
            warmth = Warmth.WARMING
            val res = e.load()
            warmth = if (res.isSuccess) Warmth.WARM else Warmth.COLD
            Log.i("Warmth", "preload ${e.engineId} -> $warmth")
        }
    }

    fun markActive() {
        lastActiveMs = System.currentTimeMillis()
        warmth = Warmth.ACTIVE
        // schedule downgrade after idle 90s
        scope.launch {
            delay(90_000)
            if (System.currentTimeMillis() - lastActiveMs >= 89_000 && warmth == Warmth.ACTIVE) {
                warmth = Warmth.WARM
            }
        }
    }

    fun onTrimMemory(level: Int) {
        // Only unload on truly critical/complete trims, not moderate
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            scope.launch {
                currentEngine?.unload()
                warmth = Warmth.UNLOADED
                Log.i("Warmth", "trim memory critical $level -> unload")
            }
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Log.i("Warmth", "trim memory low $level -> keep warm, not unloading")
        }
    }

    fun release() {
        val engine = currentEngine
        scope.cancel()
        // Unload synchronously if needed after cancel, use new scope
        if (engine != null) {
            CoroutineScope(Dispatchers.IO).launch { try { engine.unload() } catch (_: Exception) {} }
        }
    }
}
