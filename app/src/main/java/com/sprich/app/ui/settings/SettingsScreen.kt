package com.sprich.app.ui.settings

import android.app.ActivityManager
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.sprich.app.BuildConfig
import com.sprich.app.R
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.storage.Preferences
import com.sprich.app.ui.setup.ModelSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun SettingsScreen(onBack: () -> Unit, onBenchmark: () -> Unit, onVocab: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { Preferences(context) }
    val scope = rememberCoroutineScope()
    val language by prefs.speechLanguage.collectAsState(initial = SpeechLanguage.Auto)
    val instant by prefs.instantMode.collectAsState(initial = false)
    val haptics by prefs.haptics.collectAsState(initial = true)
    val commands by prefs.commands.collectAsState(initial = true)
    var advanced by rememberSaveable { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    val automatic = language is SpeechLanguage.Auto
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings)) }, windowInsets = WindowInsets(0), navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.selectableGroup()) {
                ModeChoice(stringResource(R.string.automatic), stringResource(R.string.automatic_short), automatic) { scope.launch { prefs.setSpeechLanguage(SpeechLanguage.Auto) } }
                ModeChoice(stringResource(R.string.accurate), stringResource(R.string.accurate_short), !automatic) { if (automatic) scope.launch { prefs.setSpeechLanguage(SpeechLanguage.Fixed("en")) } }
            }
            if (!automatic) {
                Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("en" to R.string.ime_subtype_en, "de" to R.string.ime_subtype_de, "es" to R.string.ime_subtype_es, "fr" to R.string.ime_subtype_fr).forEach { (tag, title) ->
                        FilterChip(selected = (language as? SpeechLanguage.Fixed)?.tag == tag, onClick = { scope.launch { prefs.setSpeechLanguage(SpeechLanguage.Fixed(tag)) } }, label = { Text(stringResource(title)) })
                    }
                }
            }
            ModelSetup(automatic)
            HorizontalDivider()
            SettingsToggle(stringResource(R.string.instant_dictation), stringResource(R.string.instant_description), instant) { scope.launch { prefs.setInstantMode(it) } }
            SettingsToggle(stringResource(R.string.haptics), stringResource(R.string.haptics_description), haptics) { scope.launch { prefs.setHaptics(it) } }
            SettingsToggle(stringResource(R.string.spoken_editing), stringResource(R.string.commands_description), commands) { scope.launch { prefs.setCommands(it) } }
            OutlinedButton(onClick = onVocab, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.personal_vocabulary)) }
            HorizontalDivider()
            Text(stringResource(R.string.using_sprich), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.gesture_help), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.cursor_help), style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            Text(stringResource(R.string.privacy), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.privacy_offline), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.privacy_downloads), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { advanced = !advanced }) { Text(stringResource(if (advanced) R.string.hide_advanced else R.string.advanced)) }
            if (advanced) {
                Text(stringResource(R.string.model_details), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.cloud_unavailable), style = MaterialTheme.typography.bodyMedium)
                ExperimentalCloudSettings(prefs)
                if (BuildConfig.ENABLE_BENCHMARK) TextButton(onClick = onBenchmark) { Text(stringResource(R.string.debug_tools)) }
                TextButton(onClick = { licenses = true }) { Text(stringResource(R.string.licenses)) }
                TextButton(onClick = { clearConfirm = true }) { Text(stringResource(R.string.clear_data), color = MaterialTheme.colorScheme.error) }
            }
            Text(stringResource(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), style = MaterialTheme.typography.labelSmall)
        }
    }
    if (clearConfirm) AlertDialog(
        onDismissRequest = { clearConfirm = false }, title = { Text(stringResource(R.string.clear_data_question)) },
        text = { Text(stringResource(R.string.clear_data_description)) },
        confirmButton = { TextButton(onClick = {
            clearConfirm = false
            if (!context.getSystemService(ActivityManager::class.java).clearApplicationUserData()) Toast.makeText(context, R.string.clear_data_failed, Toast.LENGTH_LONG).show()
        }) { Text(stringResource(R.string.clear_data)) } },
        dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (licenses) {
        var notices by remember { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(Unit) { notices = withContext(Dispatchers.IO) { context.assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText().split("\n\n").filter(String::isNotBlank) } } }
        AlertDialog(onDismissRequest = { licenses = false }, title = { Text(stringResource(R.string.licenses)) },
            text = { LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { items(notices) { Text(it, style = MaterialTheme.typography.bodySmall) } } },
            confirmButton = { TextButton(onClick = { licenses = false }) { Text(stringResource(R.string.close)) } })
    }
}

@Composable private fun ModeChoice(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(description, style = MaterialTheme.typography.bodyMedium) }
    }
}
@Composable private fun SettingsToggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked = checked, onCheckedChange = null)
    }
}
