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
    /**
     * U03: process-wide recovery draft (in-memory, 16,384 chars, 120 s TTL —
     * see RecoveryDraftStore). Owned here so the IME (saves on failed commit)
     * and Home (review/discard card) share it; cleared on screen lock.
     * [recoveryRevision] bumps on every save/consume/discard for Compose.
     */
    val recoveryDrafts = com.sprich.app.storage.RecoveryDraftStore()
    val recoveryRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    fun noteRecoveryChanged() { recoveryRevision.value++ }

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
