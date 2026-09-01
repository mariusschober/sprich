package com.sprich.app

import android.app.Application
import android.os.StrictMode
import android.util.Log
import com.sprich.app.diagnostics.Diagnostics
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.speech.api.EngineType
import com.sprich.app.speech.canary.CanaryEngine
import com.sprich.app.storage.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SprichApp : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var modelManager: ModelManager
        private set

    lateinit var canaryEngine: CanaryEngine
        private set

    // Kept for benchmark compatibility; now aliases Canary. Whisper deleted per user request.
    val fastEngine get(): CanaryEngine = canaryEngine

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build(),
            )
        }

        // One process-wide model manager and Canary instance. Every screen and the IME now
        // observe the same download/readiness state instead of constructing stale local copies.
        modelManager = ModelManager(this)
        canaryEngine = CanaryEngine(this, modelManager)
        applicationScope.launch {
            Diagnostics.capturePreviousExit(this@SprichApp)
            runCatching { modelManager.checkIntegrity() }
            Preferences(this@SprichApp).setEngine(EngineType.ACCURATE)
            val result = canaryEngine.load()
            if (result.isFailure) {
                Log.e(
                    "SprichApp",
                    "Canary preload failed (model not yet downloaded)",
                    result.exceptionOrNull(),
                )
            } else {
                Log.i("SprichApp", "Canary preload success")
            }
        }

        // Global crash breadcrumb — local only, no network.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val file = java.io.File(filesDir, "diagnostics/crash.log")
                file.parentFile?.mkdirs()
                file.writeText(
                    "crash ${System.currentTimeMillis()} thread=${thread.name} " +
                        error.stackTraceToString().take(4000),
                )
            } catch (_: Exception) {
                // Crash reporting must never replace the original exception.
            }
            previousHandler?.uncaughtException(thread, error)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::canaryEngine.isInitialized) {
            Log.i(
                "SprichApp",
                "memory trim level=$level engineLoaded=${canaryEngine.isLoaded()}",
            )
        }
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
