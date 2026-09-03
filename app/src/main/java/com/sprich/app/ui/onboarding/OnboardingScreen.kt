package com.sprich.app.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.sprich.app.R
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.models.manager.ModelStatus
import com.sprich.app.storage.Preferences
import com.sprich.app.ui.setup.*
import com.sprich.app.ui.theme.VoiceMark
import kotlinx.coroutines.launch

@Composable fun OnboardingScreen(onDone: () -> Unit, onOpenImeSettings: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Preferences(context) }
    val manager = remember { ModelManager(context) }
    val lid by manager.lidStatus.collectAsState()
    val fast by manager.fastConformerStatus.collectAsState()
    val keyboard = rememberKeyboardState()
    val instantStart by prefs.instantMode.collectAsState(initial = true)
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    val ready = lid is ModelStatus.Ready && fast is ModelStatus.Ready
    fun done() { scope.launch { prefs.setOnboardingDone(true); onDone() } }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(stringResource(R.string.setup_step, step + 1, 4), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (step) {
            0 -> {
                VoiceMark(Modifier.size(112.dp))
                Text(stringResource(R.string.onboard_title), style = MaterialTheme.typography.headlineLarge)
                Text(stringResource(R.string.onboard_description), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.onboard_privacy), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.get_started)) }
            }
            1 -> {
                Text(stringResource(R.string.microphone_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.microphone_description), style = MaterialTheme.typography.bodyLarge)
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(instantStart, role = Role.Switch) { scope.launch { prefs.setInstantMode(it) } }
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.instant_dictation), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.instant_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = instantStart, onCheckedChange = null)
                    }
                }
                if (keyboard.microphone) Button(onClick = { step = 2 }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.continue_action)) }
                else MicrophoneAccess { step = 2 }
            }
            2 -> {
                Text(stringResource(R.string.enable_keyboard), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.keyboard_description), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.keyboard_warning), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (keyboard.enabled) {
                    Text(stringResource(R.string.keyboard_enabled))
                    Button(onClick = { step = 3 }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.continue_action)) }
                } else Button(onClick = onOpenImeSettings, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.open_keyboard_settings)) }
            }
            else -> {
                Text(stringResource(R.string.setup_offline), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.setup_offline_description), style = MaterialTheme.typography.bodyLarge)
                ModelSetup(automatic = true)
                if (ready) Button(onClick = { done() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.try_dictation)) }
            }
        }
        if (step > 0) TextButton(onClick = { step-- }) { Text(stringResource(R.string.back)) }
        TextButton(onClick = { done() }) { Text(stringResource(R.string.finish_later)) }
    }
}
