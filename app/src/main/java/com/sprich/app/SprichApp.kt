package com.sprich.app

import android.app.Application
import android.os.StrictMode
import android.util.Log
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.speech.api.EngineType
import com.sprich.app.speech.canary.CanaryEngine
import com.sprich.app.storage.Preferences
import com.sprich.app.diagnostics.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SprichApp : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var canaryEngine: CanaryEngine
        private set
    // Kept for benchmark compatibility; now aliases Canary. Whisper deleted per user request.
    val fastEngine get(): CanaryEngine = canaryEngine

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build())
        }
        // One process-wide Canary instance. Whisper deleted per user request — Canary 180M Flash INT8 is now primary.
        val mm = ModelManager(this)
        canaryEngine = CanaryEngine(this, mm)
        applicationScope.launch {
            Diagnostics.capturePreviousExit(this@SprichApp)
            // Clean stale optional-model staging directories off the main thread.
            runCatching {
                mm.checkIntegrity()
            }
            // Do NOT unconditionally preload Canary — Automatic winner is Tiny LID + FastConformer (224 MB), not Canary (198 MB).
            // Preload is delegated to SprichIME selective logic based on speechLanguage and isAutomaticReady().
            // Keeps memory target: LID+Fast resident for Automatic, not LID+Fast+Canary.
            Log.i("SprichApp", "CanaryEngine initialized, selective preload deferred to SprichIME (Automatic = FastConformer, Accurate = Canary)")
        }
        // Global crash breadcrumb — local only, no network
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val f = java.io.File(filesDir, "diagnostics/crash.log")
                f.parentFile?.mkdirs()
                f.writeText("crash ${System.currentTimeMillis()} thread=${t.name} ${e.stackTraceToString().take(4000)}")
            } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::canaryEngine.isInitialized) {
            Log.i("SprichApp", "memory trim level=$level engineLoaded=${canaryEngine.isLoaded()}")
        }
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
