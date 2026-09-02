package com.sprich.app.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sprich.app.BuildConfig
import com.sprich.app.R
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.models.manager.ModelStatus
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.storage.Preferences
import com.sprich.app.ui.setup.*

@Composable fun HomeScreen(onSettings: () -> Unit, onBenchmarkTap: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Preferences(context) }
    val manager = remember { ModelManager(context) }
    val language by prefs.speechLanguage.collectAsState(initial = SpeechLanguage.Auto)
    val lid by manager.lidStatus.collectAsState()
    val fast by manager.fastConformerStatus.collectAsState()
    val accurate by manager.canaryStatus.collectAsState()
    val automatic = language is SpeechLanguage.Auto
    val modelsReady = if (automatic) lid is ModelStatus.Ready && fast is ModelStatus.Ready else accurate is ModelStatus.Ready
    val keyboard = rememberKeyboardState()
    val ready = keyboard.enabled && keyboard.microphone && modelsReady
    // Trial dictation is ephemeral; never serialize it into Android's saved-state storage.
    var trial by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings)) }
        }
        Text(stringResource(if (ready) R.string.home_ready else R.string.finish_setup), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(if (automatic) R.string.automatic_languages else R.string.accurate_languages), color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            !keyboard.microphone -> MicrophoneAccess()
            !keyboard.enabled -> Button(onClick = { openKeyboardSettings(context) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.enable_keyboard)) }
            !modelsReady -> ModelSetup(automatic)
            !keyboard.selected -> Button(onClick = { showKeyboardPicker(context) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.choose_keyboard)) }
        }
        if (ready) {
            OutlinedTextField(
                value = trial, onValueChange = { trial = it },
                label = { Text(stringResource(R.string.try_dictation)) },
                placeholder = { Text(stringResource(R.string.dictation_placeholder)) },
                minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.dictation_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { showKeyboardPicker(context) }) { Text(stringResource(R.string.switch_keyboard)) }
        }
        Text(stringResource(R.string.privacy_offline), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (BuildConfig.ENABLE_BENCHMARK) TextButton(onClick = onBenchmarkTap) { Text(stringResource(R.string.debug_tools)) }
    }
}
