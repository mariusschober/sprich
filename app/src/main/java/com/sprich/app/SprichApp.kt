package com.sprich.app

import android.app.Application
import android.os.StrictMode
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.speech.canary.CanaryEngine
import com.sprich.app.diagnostics.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SprichApp : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Benchmark-only owner. Production IMEs own and retire their own recognizers.
    val fastEngine: CanaryEngine by lazy { CanaryEngine(this, ModelManager(this)) }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build())
        }
        ModelManager(this) // One process-wide asynchronous install verification.
        applicationScope.launch { Diagnostics.capturePreviousExit(this@SprichApp) }
        // Global crash breadcrumb — local only, no network
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val f = java.io.File(filesDir, "diagnostics/crash.log")
                f.parentFile?.mkdirs()
                f.writeText("crash ${System.currentTimeMillis()} thread=${t.name} ${e.javaClass.name}\n${e.stackTrace.take(24).joinToString("\n")}")
            } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
