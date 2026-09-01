package com.sprich.app.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sprich.app.SprichApp
import com.sprich.app.models.manager.ModelStatus
import com.sprich.app.speech.api.Language
import com.sprich.app.storage.Preferences

@Composable
fun HomeScreen(onSettings: () -> Unit, onBenchmarkTap: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SprichApp
    val prefs = remember { Preferences(context) }
    val modelStatus by app.modelManager.canaryStatus.collectAsState()
    val language by prefs.language.collectAsState(initial = Language.EN)
    var versionTap by remember { mutableIntStateOf(0) }
    var trial by remember { mutableStateOf("") }

    val modelReady = modelStatus is ModelStatus.Ready
    val languageLabel = when (language) {
        Language.DE -> "Deutsch"
        Language.ES -> "Español"
        Language.FR -> "Français"
        else -> "English"
    }
    val statusTitle = when (modelStatus) {
        ModelStatus.NotDownloaded -> "Finish setup"
        is ModelStatus.Downloading -> "Downloading model"
        ModelStatus.Verifying -> "Preparing model"
        ModelStatus.Ready -> "Ready"
        is ModelStatus.Failed -> "Setup needs attention"
    }
    val statusDetail = when (val status = modelStatus) {
        ModelStatus.NotDownloaded -> "Install the local speech model before dictating"
        is ModelStatus.Downloading -> "${(status.progress * 100).toInt()}% · Keep Sprich open"
        ModelStatus.Verifying -> "Verifying local model files"
        ModelStatus.Ready -> "Canary · $languageLabel · On-device"
        is ModelStatus.Failed -> status.error.take(72)
    }

    val infinite = rememberInfiniteTransition(label = "homeBreath")
    val scale by infinite.animateFloat(
        initialValue = 0.99f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "orb",
    )
    val alpha by infinite.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.14f,
        animationSpec = infiniteRepeatable(tween(2200), RepeatMode.Reverse),
        label = "halo",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp)
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(112.dp)) {
            Box(
                Modifier
                    .size(112.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = alpha)),
            )
            Box(
                Modifier
                    .size(96.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(36.dp)) {
                    drawCircle(color = Color.White.copy(alpha = 0.94f))
                }
                Canvas(Modifier.size(30.dp)) {
                    drawArc(
                        color = Color.White,
                        startAngle = 20f,
                        sweepAngle = 140f,
                        useCenter = false,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            statusTitle,
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            statusDetail,
            style = MaterialTheme.typography.bodySmall,
            color = if (modelStatus is ModelStatus.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))

        if (!modelReady) {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("One step before dictation", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Choose your language and install the speech model. Sprich will only show Ready after the model has been verified on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                        Text(if (modelStatus is ModelStatus.Failed) "Open setup and retry" else "Finish setup")
                    }
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.tertiary),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("Try dictation", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                    ) {
                        Box(Modifier.padding(14.dp)) {
                            if (trial.isEmpty()) {
                                Text(
                                    "Select the Sprich keyboard, tap here, and speak…",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            BasicTextField(
                                value = trial,
                                onValueChange = { trial = it },
                                modifier = Modifier.fillMaxSize(),
                                textStyle = LocalTextStyle.current.copy(
                                    fontSize = 16.sp,
                                    lineHeight = 22.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Instant Dictation inserts directly at the cursor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = {
                versionTap++
                if (versionTap >= 7) onBenchmarkTap()
            },
        ) {
            Text(
                "Sprich 1.0.0  •  tap 7× for benchmark",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}
