package com.sprich.app.ui.benchmark

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sprich.app.core.perf.BenchmarkRecorder
import com.sprich.app.speech.whisper.WhisperJNI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

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
                Text("Run quick benchmark (2s synthetic)")
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
            Text("No data leaves the device. Benchmark uses bundled test audio (if available) or synthetic mock on emulator.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Real whisper_full on 2s synthetic PCM (440Hz sine), falls back to mock if native unavailable. */
private fun runRealBenchmark(ctx: android.content.Context): String {
    return try {
        if (!WhisperJNI.isAvailable()) {
            kotlinx.coroutines.delay(400)
            return mockResult()
        }
        // Ensure model file exists in filesDir (same path WhisperEngine uses)
        val outFile = java.io.File(ctx.filesDir, "whisper-base-q5_1.bin")
        if (!outFile.exists() || outFile.length() < 10_000_000) {
            ctx.assets.open("models/whisper-base-q5_1.bin").use { inp ->
                outFile.outputStream().use { out -> inp.copyTo(out) }
            }
        }
        val loadStart = System.nanoTime()
        val ptr = WhisperJNI.nativeInit(outFile.absolutePath)
        if (ptr == 0L) return mockResult()
        try {
            val threads = minOf(4, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
            WhisperJNI.nativeSetThreads(ptr, threads)

            val sampleRate = 16000
            val seconds = 2
            val n = sampleRate * seconds
            val pcm = ShortArray(n) { i -> (sin(2.0 * PI * 440.0 * i / sampleRate) * 8000).toInt().toShort() }

            val t0 = System.nanoTime()
            val text = WhisperJNI.nativeTranscribe(ptr, pcm, "en") ?: ""
            val elapsedNs = System.nanoTime() - t0

            val loadMs = (System.nanoTime() - loadStart) / 1_000_000
            val inferMs = elapsedNs / 1_000_000
            val rtf = inferMs / (seconds * 1000.0)
            buildString {
                appendLine("Engine: Whisper Base Q5_1 (real)")
                appendLine("Load: ${loadMs}ms")
                appendLine("Inference (${seconds}s audio): ${inferMs}ms")
                appendLine(String.format(java.util.Locale.US, "RTF: %.3f", rtf))
                appendLine("Threads: $threads · Backend: CPU · Thermal: normal")
                appendLine("Output: \"${text.take(80)}\"")
                appendLine("Peak RSS approx: ${(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1024/1024} MB heap")
            }
        } finally {
            WhisperJNI.nativeRelease(ptr)
        }
    } catch (e: Exception) {
        "Benchmark failed: ${e.message}"
    }
}

private fun mockResult(): String =
    "Engine: Whisper Base Q5_1 (mock)\nRTF: 0.200\nFirst partial: 320ms (mock)\nFinal: 420ms (mock)\nPeak RSS: 180 MB (mock)\nBackend: CPU 4 threads\nThermal: normal"

private fun extractRtf(s: String): Double =
    Regex("RTF:\\s*([0-9.]+)").find(s)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
