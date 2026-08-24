package com.sprich.app

import android.app.Application
import android.os.StrictMode
import com.sprich.app.speech.warmup.ModelWarmthManager

class SprichApp : Application() {
    lateinit var warmthManager: ModelWarmthManager

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build())
        }
        warmthManager = ModelWarmthManager(this)
        // Integrity check — clean stale .tmp dirs from interrupted downloads
        try { com.sprich.app.models.manager.ModelManager(this).checkIntegrity() } catch (_: Exception) {}
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
        if (::warmthManager.isInitialized) warmthManager.onTrimMemory(level)
    }
}
