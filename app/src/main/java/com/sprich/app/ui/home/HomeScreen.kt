package com.sprich.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sprich.app.BuildConfig
import com.sprich.app.R
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.models.manager.ModelStatus
import com.sprich.app.speech.TranscriptionMode
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.speech.refinement.RefinementMode
import com.sprich.app.storage.Preferences
import com.sprich.app.storage.ApiSecretStore
import com.sprich.app.api.ApiUse
import com.sprich.app.api.apiChoice
import com.sprich.app.ui.setup.*
import com.sprich.app.ui.theme.VoiceMark

@Composable fun HomeScreen(onSettings: () -> Unit, onBenchmarkTap: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Preferences(context) }
    val manager = remember { ModelManager(context) }
    val config by prefs.runtimeConfigSnapshot.collectAsState(initial = null)
    val lid by manager.lidStatus.collectAsState()
    val fast by manager.fastConformerStatus.collectAsState()
    val accurate by manager.canaryStatus.collectAsState()
    val automatic = config?.speechLanguage !is SpeechLanguage.Fixed
    val api = config?.transcriptionMode == TranscriptionMode.API_PRIMARY
    val voiceChoice = config?.apiChoice(ApiUse.VOICE)
    val keyAvailable by produceState<Boolean?>(initialValue = null, api, voiceChoice) {
        value = if (!api) true else voiceChoice?.let { !ApiSecretStore(context).loadBoundSecret(it.credentialRef, it.providerId, it.endpoint).isNullOrBlank() } == true
    }
    val cleanup = config?.refinementMode?.let { it != RefinementMode.OFF } == true
    val modelsReady = if (automatic) lid is ModelStatus.Ready && fast is ModelStatus.Ready else accurate is ModelStatus.Ready
    val keyboard = rememberKeyboardState()
    val ready = config != null && keyboard.enabled && keyboard.microphone && (if (api) keyAvailable == true else modelsReady)
    // Dictation is deliberately ephemeral, including across process recreation.
    var trial by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            VoiceMark(Modifier.size(36.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 4.dp))
            IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings)) }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VoiceMark(Modifier.size(136.dp))
            Text(stringResource(R.string.onboard_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.home_invitation), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        }
        Surface(shape = RoundedCornerShape(50), color = colors.surfaceContainer, border = BorderStroke(1.dp, colors.outlineVariant)) {
            Text(stringResource(when { api && cleanup -> R.string.path_api_cleanup; api -> R.string.path_api; cleanup -> R.string.path_local_cleanup; else -> R.string.path_local }),
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        if (ready) {
            Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerLow, border = BorderStroke(1.dp, colors.outlineVariant), shadowElevation = 2.dp) {
                Column(Modifier.background(Brush.linearGradient(listOf(Color(0x0FFF7A67), Color.Transparent))).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.try_dictation), style = MaterialTheme.typography.titleSmall)
                    TextField(
                        value = trial, onValueChange = { trial = it },
                        placeholder = { Text(stringResource(R.string.dictation_placeholder)) },
                        minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    )
                    Text(
                        stringResource(if (config?.instantMode == true) R.string.dictation_help_instant else R.string.dictation_help_manual),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
        when {
            config == null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            !keyboard.microphone -> MicrophoneAccess()
            !keyboard.enabled -> Button(onClick = { openKeyboardSettings(context) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.enable_keyboard)) }
            api && keyAvailable == false -> Button(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.api_check_key)) }
            !api && !modelsReady -> ModelSetup(automatic)
            !keyboard.selected -> Button(onClick = { showKeyboardPicker(context) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.choose_keyboard)) }
        }
        Text(stringResource(R.string.automatic_languages), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        if (ready) TextButton(onClick = { showKeyboardPicker(context) }) { Text(stringResource(R.string.switch_keyboard)) }
        if (BuildConfig.ENABLE_BENCHMARK) TextButton(onClick = onBenchmarkTap) { Text(stringResource(R.string.debug_tools)) }
    }
}
