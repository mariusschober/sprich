package com.sprich.app.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sprich.app.storage.Preferences
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(onDone: ()->Unit, onOpenImeSettings: ()->Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Preferences(ctx) }
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var trialText by remember { mutableStateOf("") }
    // Hoisted launcher — never inside conditional (Compose hook rule)
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> step = 2 }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp).imePadding(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        when (step) {
            0 -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(16.dp))
                PleasureDotHero()
                Spacer(Modifier.height(24.dp))
                OnboardPage(
                    title = "Speak. It’s there.",
                    sub = "Local speech-to-text for every text field.",
                    button = "Continue",
                    onClick = { step = 1 }
                )
            }
            1 -> {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    PleasureDotHero(small = true)
                    Spacer(Modifier.height(16.dp))
                }
                OnboardPage(
                    title = "Nothing leaves your phone.",
                    bullets = listOf("Speech is processed locally", "Audio is not saved", "Works offline"),
                    button = if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "Continue" else "Allow microphone",
                    onClick = {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) step = 2
                        else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                )
            }
            2 -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                // Pleasure accent number illustration
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary), contentAlignment = Alignment.Center) {
                        Text("1", color = MaterialTheme.colorScheme.onTertiary, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("Enable Sprich keyboard", style = MaterialTheme.typography.headlineSmall)
                }
                Spacer(Modifier.height(12.dp))
                Text("Sprich works as a keyboard so it can insert text at the cursor. You can still switch to Gboard anytime.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                // Visual guide — pleasure dot + arrow
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.size(22.dp)) { drawCircle(color = androidx.compose.ui.graphics.Color.White) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Settings → System → Keyboard", style = MaterialTheme.typography.labelMedium)
                            Text("Turn on Sprich", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Button(onClick = onOpenImeSettings, modifier = Modifier.fillMaxWidth()) { Text("Open keyboard settings") }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { step = 3 }, modifier = Modifier.fillMaxWidth()) { Text("I’ve enabled it → Continue") }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { step = 3 }) { Text("Skip for now") }
            }
            3 -> LanguagePickerStep(
                prefs = prefs,
                onPicked = { tag ->
                    scope.launch {
                        // Explicit user pick — never infer from locale silently
                        val lang = when(tag){
                            "de" -> com.sprich.app.speech.api.SpeechLanguage.Fixed("de")
                            "es" -> com.sprich.app.speech.api.SpeechLanguage.Fixed("es")
                            "fr" -> com.sprich.app.speech.api.SpeechLanguage.Fixed("fr")
                            else -> com.sprich.app.speech.api.SpeechLanguage.Fixed("en")
                        }
                        prefs.setSpeechLanguage(lang)
                        step = 4
                    }
                },
                onSkipSuggestion = { step = 4 }
            )
            4 -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Try it.", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("Tap the field and say:\n“This is much faster than typing.”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(20.dp))
                // Check if Sprich is current IME — if not, show pleasure banner
                val isSprichIme = remember {
                    val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    val id = android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
                    id.contains("sprich")
                }
                if (!isSprichIme) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
                            Spacer(Modifier.width(8.dp))
                            Text("Switch to Sprich keyboard first ↑", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth().height(140.dp)
                ) {
                    Box(Modifier.padding(16.dp)) {
                        if (trialText.isEmpty()) {
                            Text("Tap here to type…", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.6f), style = MaterialTheme.typography.bodyLarge)
                        }
                        BasicTextField(
                            value = trialText,
                            onValueChange = { trialText = it },
                            modifier = Modifier.fillMaxSize(),
                            textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, lineHeight = 22.sp),
                            decorationBox = { inner -> inner() }
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        scope.launch {
                            prefs.setOnboardingDone(true)
                            prefs.setInstantMode(true)
                            onDone()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Done — enable Instant Dictation") }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    scope.launch { prefs.setOnboardingDone(true); onDone() }
                }, modifier = Modifier.fillMaxWidth()) { Text("Continue without Instant Mode") }
            }
        }
        // Spring step indicator — pleasure + bouncy (5 steps: intro, mic, keyboard, language, trial)
        Row(Modifier.fillMaxWidth().navigationBarsPadding(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            repeat(5) { i ->
                val selected = i == step
                val width by animateDpAsState(if (selected) 20.dp else 8.dp, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "dotW")
                val color by animateColorAsState(if (selected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline, label = "dotC")
                Box(
                    Modifier.padding(4.dp).size(width, 4.dp).clip(RoundedCornerShape(2.dp)).background(color)
                )
            }
        }
    }
}

@Composable
private fun LanguagePickerStep(
    prefs: Preferences,
    onPicked: (String)->Unit,
    onSkipSuggestion: ()->Unit,
) {
    val localeTag = remember {
        try { java.util.Locale.getDefault().toLanguageTag() } catch (_: Exception) { "en" }
    }
    val suggestion = remember(localeTag) {
        when {
            localeTag.lowercase().startsWith("de") -> "de"
            localeTag.lowercase().startsWith("es") -> "es"
            localeTag.lowercase().startsWith("fr") -> "fr"
            localeTag.lowercase().startsWith("en") -> "en"
            else -> null
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Text("Choose your language", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("Pick the language you speak most. This is required — Canary has no native Automatic detection. You can change it later in Settings.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (suggestion != null) {
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.tertiary.copy(alpha=0.12f)) {
                Text("Suggestion from your phone: ${suggestion.uppercase()} — tap to confirm or pick another.", modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        Spacer(Modifier.height(20.dp))
        val options = listOf("en" to "English", "de" to "Deutsch", "es" to "Español", "fr" to "Français")
        options.forEach { (tag, label) ->
            val isSuggested = tag == suggestion
            OutlinedButton(
                onClick = { onPicked(tag) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = if (isSuggested) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha=0.12f)) else ButtonDefaults.outlinedButtonColors()
            ) {
                Text("$label  ($tag)" + if (isSuggested) " — suggested" else "", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Automatic detection is not available on-device for Canary. An explicit language ensures accurate transcription.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PleasureDotHero(small: Boolean = false) {
    val infinite = rememberInfiniteTransition(label = "pleasureBreath")
    val scale by infinite.animateFloat(initialValue = 0.98f, targetValue = 1.03f, animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale")
    val size = if (small) 64.dp else 96.dp
    Box(Modifier.size(size).scale(scale).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(if (small) 22.dp else 32.dp)) {
            drawCircle(color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.94f))
        }
        // Smile cut — subtle white arc mimicking SVG breath
        Canvas(Modifier.size(if (small) 20.dp else 28.dp)) {
            val stroke = 2.dp.toPx()
            drawArc(color = androidx.compose.ui.graphics.Color.White, startAngle = 20f, sweepAngle = 140f, useCenter = false, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
    }
}

@Composable
private fun OnboardPage(title: String, sub: String? = null, bullets: List<String>? = null, button: String, onClick: ()->Unit){
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        if (bullets != null) {
            Spacer(Modifier.height(16.dp))
            bullets.forEach {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
                    Spacer(Modifier.width(12.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(button) }
    }
}
