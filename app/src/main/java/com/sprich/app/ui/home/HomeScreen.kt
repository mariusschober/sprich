package com.sprich.app.ui.home

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sprich.app.speech.api.EngineType
import com.sprich.app.storage.Preferences

@Composable
fun HomeScreen(onSettings: ()->Unit, onBenchmarkTap: ()->Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Preferences(ctx) }
    val speechLang by prefs.speechLanguage.collectAsState(initial = com.sprich.app.speech.api.SpeechLanguage.Auto)
    var versionTap by remember { mutableIntStateOf(0) }
    var trial by remember { mutableStateOf("") }
    val infinite = rememberInfiniteTransition(label = "homeBreath")
    val scale by infinite.animateFloat(initialValue = 0.99f, targetValue = 1.04f, animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "orb")
    val alpha by infinite.animateFloat(initialValue = 0.08f, targetValue = 0.14f, animationSpec = infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "halo")

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp).imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.height(8.dp))
        // Hero orb — pleasure dot breathing, quiet halo
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(112.dp)) {
            Box(Modifier.size(112.dp).scale(scale).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary.copy(alpha = alpha)))
            Box(Modifier.size(96.dp).scale(scale).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(36.dp)) { drawCircle(color = Color.White.copy(alpha = 0.94f)) }
                Canvas(Modifier.size(30.dp)) {
                    drawArc(color = Color.White, startAngle = 20f, sweepAngle = 140f, useCenter = false, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Ready to speak", style = MaterialTheme.typography.displaySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        val langLabel = when (speechLang) {
            is com.sprich.app.speech.api.SpeechLanguage.Fixed -> when ((speechLang as com.sprich.app.speech.api.SpeechLanguage.Fixed).tag) {
                "de" -> "Deutsch"; "es" -> "Español"; "fr" -> "Français"; else -> "English"
            }
            else -> "Automatic"
        }
        Text("$langLabel · English · Deutsch · Español · Français · On-device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
                    Spacer(Modifier.width(8.dp))
                    Text("Try dictation", style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().height(120.dp)) {
                    Box(Modifier.padding(14.dp)) {
                        if (trial.isEmpty()) Text("Select Sprich keyboard, tap here and speak…", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.6f), style = MaterialTheme.typography.bodyMedium)
                        BasicTextField(
                            value = trial,
                            onValueChange = { trial = it },
                            modifier = Modifier.fillMaxSize(),
                            textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurface)
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Text appears where you type.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.weight(1f))
        // Release: version from actual package, benchmark hidden (debug-only route); no 1.0.0 hardcode, no 7× tap
        val pkgInfo = try {
            val pm = ctx.packageManager
            val info = if (android.os.Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0)) else @Suppress("DEPRECATION") pm.getPackageInfo(ctx.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        } catch (_: Exception) { "" }
        val isDebugBenchmark = try { com.sprich.app.BuildConfig.ENABLE_BENCHMARK } catch (_: Exception) { false }
        // Derive status from atomic runtime config (truthful, not always On-device)
        val runtimeSnap by prefs.runtimeConfigSnapshot.collectAsState(initial = null)
        val statusTextDerived = run {
            val snap = runtimeSnap ?: return@run null
            val mode = snap.transcriptionMode
            val lang = snap.speechLanguage
            val provider = snap.sttProviderId
            when (mode) {
                com.sprich.app.speech.TranscriptionMode.API_PRIMARY -> when (provider) {
                    "meta-muse-voice-transcribe", "meta-muse" -> "Online · Muse Voice"
                    "gemini", "gemini-3.5-transcribe" -> "Online · Gemini"
                    else -> "Online · Custom"
                }
                com.sprich.app.speech.TranscriptionMode.LOCAL_API_FALLBACK -> "Automatic · On device if possible, online if needed"
                else -> when (lang) {
                    is com.sprich.app.speech.api.SpeechLanguage.Auto -> "Automatic · On device"
                    is com.sprich.app.speech.api.SpeechLanguage.Fixed -> when (lang.tag) {
                        "de" -> "German · On device"; "es" -> "Español · On device"; "fr" -> "Français · On device"; else -> "English · On device"
                    }
                }
            }
        }
        if (statusTextDerived != null) {
            Text(statusTextDerived, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        if (isDebugBenchmark) {
            TextButton(onClick = {
                versionTap++
                if (versionTap >= 7) onBenchmarkTap()
            }) {
                Text("Sprich $pkgInfo  •  tap 7× for benchmark (debug)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.8f))
            }
        } else {
            Text("Sprich $pkgInfo", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.8f))
        }
        Spacer(Modifier.height(4.dp))
    }
}
