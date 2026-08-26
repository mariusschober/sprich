package com.sprich.app.ui.benchmark

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sprich.app.SprichApp
import com.sprich.app.core.audio.Pcm16Wav
import com.sprich.app.core.perf.BenchmarkRecorder
import com.sprich.app.speech.api.Language
import com.sprich.app.speech.api.SpeechSessionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BenchmarkScreen(onBack: ()->Unit){
    val ctx = LocalContext.current
    var result by remember { mutableStateOf("No benchmark run yet.") }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            CenterAlignedTopAppBar(title={ Text("Benchmark")}, navigationIcon = { TextButton(onClick=onBack){ Text("Back")} })
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)){
            Text("Device & engine performance", style = MaterialTheme.typography.titleMedium)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)){
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)){
                    Text("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}", style = MaterialTheme.typography.bodySmall)
                    Text("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})", style = MaterialTheme.typography.bodySmall)
                    Text("SoC: ${android.os.Build.HARDWARE} • ${if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL ?: "unknown" else "unknown"}", style = MaterialTheme.typography.bodySmall)
                    Text("RAM: ~${Runtime.getRuntime().maxMemory()/1024/1024} MB max heap", style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(onClick = {
                scope.launch {
                    result = "Running benchmark…"
                    val r = withContext(Dispatchers.IO) {
                        runRealBenchmark(ctx)
                    }
                    result = r
                }
            }, modifier = Modifier.fillMaxWidth()){
                Text("Run speech-path benchmark")
            }
            Card(Modifier.fillMaxWidth()){
                Text(result, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = {
                scope.launch {
                    val rec = BenchmarkRecorder()
                    // Re-run to capture a sample for export
                    val sampleRtf = withContext(Dispatchers.IO) { extractRtf(runRealBenchmark(ctx)) }
                    rec.add(BenchmarkRecorder.Sample(deviceModel = android.os.Build.MODEL, engine = "whisper-base-q5_1", rtfs = listOf(sampleRtf)))
                    val json = rec.toJson()
                    try{
                        val f = java.io.File(ctx.filesDir, "benchmark/export.json")
                        f.parentFile?.mkdirs()
                        f.writeText(json)
                        result = "Exported to ${f.absolutePath}\n$json"
                    } catch (e:Exception){ result = "Export failed: ${e.message}"}
                }
            }, modifier = Modifier.fillMaxWidth()){
                Text("Export JSON (local only)")
            }
            Text("No data leaves the device. Benchmark uses the bundled public-domain JFK speech fixture and the same Fast engine as dictation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Runs the same process-wide engine used by the IME against deterministic real speech. */
private suspend fun runRealBenchmark(ctx: android.content.Context): String {
    return try {
        val engine = (ctx.applicationContext as SprichApp).fastEngine
        val loadStart = System.nanoTime()
        val loadResult = engine.load()
        if (loadResult.isFailure) return "Benchmark failed: ${loadResult.exceptionOrNull()?.message}"
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000
        val audio = ctx.assets.open("jfk.wav").use(Pcm16Wav::read)
        engine.cancelSession()
        engine.beginSession(SpeechSessionConfig(language = Language.EN))
        try {
            val t0 = System.nanoTime()
            engine.pushAudio(audio.samples, System.nanoTime())
            val text = engine.endUtterance().text
            val elapsedNs = System.nanoTime() - t0
            val inferMs = elapsedNs / 1_000_000
            val audioMs = audio.samples.size * 1000.0 / audio.sampleRate
            val rtf = inferMs / audioMs
            buildString {
                appendLine("Engine: Whisper Base Q5_1 (real)")
                appendLine("Load: ${loadMs}ms")
                appendLine("Inference (${audioMs.toLong()}ms speech): ${inferMs}ms")
                appendLine(String.format(java.util.Locale.US, "RTF: %.3f", rtf))
                appendLine("Backend: CPU · Thermal: device reported")
                appendLine("Output: \"${text.take(80)}\"")
                appendLine("Peak RSS approx: ${(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1024/1024} MB heap")
            }
        } finally {
            engine.cancelSession()
        }
    } catch (t: Throwable) {
        "Benchmark failed: ${t.message}"
    }
}

private fun extractRtf(s: String): Double =
    Regex("RTF:\\s*([0-9.]+)").find(s)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
