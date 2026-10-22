package com.sprich.app.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sprich.app.BuildConfig
import com.sprich.app.R
import com.sprich.app.api.ApiCatalog
import com.sprich.app.api.ApiUse
import com.sprich.app.speech.TranscriptionMode
import com.sprich.app.speech.api.SpeechLanguage
import com.sprich.app.speech.refinement.RefinementMode
import com.sprich.app.storage.Preferences
import com.sprich.app.ui.setup.ModelSetup
import com.sprich.app.ui.setup.ModelStorage
import com.sprich.app.ui.setup.openAppSettings
import com.sprich.app.ui.readableWidth
import com.sprich.app.ui.persistPreference
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun SettingsScreen(onBack: () -> Unit, onBenchmark: () -> Unit, onVocab: () -> Unit = {}, onApi: (ApiUse) -> Unit = {}, onLicenses: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { Preferences(context) }
    val scope = rememberCoroutineScope()
    val config by prefs.runtimeConfigSnapshot.collectAsState(initial = null)
    val language = config?.speechLanguage ?: SpeechLanguage.Auto
    var advanced by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    var gestures by rememberSaveable { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var clearFailed by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }
    fun write(block: suspend () -> Unit) {
        scope.launch {
            if (persistPreference({ saveError = true }) { block() }) saveError = false
        }
    }
    val automatic = language is SpeechLanguage.Auto
    val apiVoice = config?.transcriptionMode == TranscriptionMode.API_PRIMARY
    val cleanup = config?.refinementMode != null && config?.refinementMode != RefinementMode.OFF
    val languageCopy = appLanguageCopy(Build.VERSION.SDK_INT)
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings)) }, windowInsets = WindowInsets(0), navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
        })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.readableWidth().fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (saveError) Text(stringResource(R.string.pref_save_failed), color = MaterialTheme.colorScheme.error)
            SettingsGroup(stringResource(R.string.voice_typing)) {
                SettingsLink(stringResource(R.string.recognition), if (apiVoice) apiProviderName(config!!.sttProviderId) else stringResource(R.string.path_local)) { onApi(ApiUse.VOICE) }
                if (!apiVoice) {
                    HorizontalDivider()
                    Column(Modifier.selectableGroup()) {
                        ModeChoice(stringResource(R.string.automatic), stringResource(R.string.automatic_short), automatic) { write { prefs.setSpeechLanguage(SpeechLanguage.Auto) } }
                        ModeChoice(stringResource(R.string.accurate), stringResource(R.string.accurate_short), !automatic) {
                            if (automatic) write {
                                val target = accurateLanguageFor(prefs.lastAccurateLanguageTag(), java.util.Locale.getDefault().toLanguageTag())
                                prefs.setSpeechLanguage(target)
                            }
                        }
                    }
                }
                if (!automatic) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("en" to R.string.ime_subtype_en, "de" to R.string.ime_subtype_de, "es" to R.string.ime_subtype_es, "fr" to R.string.ime_subtype_fr).forEach { (tag, title) ->
                        FilterChip(selected = (language as? SpeechLanguage.Fixed)?.tag == tag, onClick = { write { prefs.setSpeechLanguage(SpeechLanguage.Fixed(tag)) } }, label = { Text(stringResource(title)) })
                    }
                }
                if (!apiVoice) ModelSetup(automatic)
                HorizontalDivider()
                ModelStorage()
            }
            SettingsGroup(stringResource(R.string.writing)) {
                SettingsLink(stringResource(R.string.cleanup_title), if (cleanup) apiProviderName(config!!.refinementProviderId) else stringResource(R.string.cleanup_off)) { onApi(ApiUse.WRITING) }
                Text(stringResource(R.string.cleanup_description), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
                SettingsLink(stringResource(R.string.personal_vocabulary), stringResource(R.string.vocab_short)) { onVocab() }
                SettingsToggle(stringResource(R.string.spoken_editing), stringResource(R.string.commands_description), config?.commandsEnabled != false) { write { prefs.setCommands(it) } }
            }
            SettingsGroup(stringResource(R.string.keyboard_title)) {
                SettingsLink(stringResource(languageCopy.title), stringResource(languageCopy.description)) { openAppLanguageSettings(context) }
                HorizontalDivider()
                SettingsToggle(stringResource(R.string.instant_dictation), stringResource(R.string.instant_description), config?.instantMode == true) { write { prefs.setInstantMode(it) } }
                SettingsToggle(stringResource(R.string.whisper_mode), stringResource(R.string.whisper_description), config?.whisperMode == true) { write { prefs.setWhisperMode(it) } }
                SettingsToggle(stringResource(R.string.haptics), stringResource(R.string.haptics_description), config?.hapticsEnabled != false) { write { prefs.setHaptics(it) } }
                TextButton(onClick = { gestures = !gestures }) { Text(stringResource(if (gestures) R.string.hide_gesture_help else R.string.bar_gestures)) }
                if (gestures) Text(stringResource(R.string.gesture_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingsGroup(stringResource(R.string.privacy_about)) {
                Text(stringResource(R.string.privacy_short), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SettingsLink(stringResource(R.string.licenses), stringResource(R.string.licenses_short), onLicenses)
                TextButton(onClick = { about = !about }) { Text(stringResource(if (about) R.string.about_less else R.string.about_more)) }
                if (about) {
                    Text(stringResource(R.string.privacy_offline), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.privacy_downloads), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.privacy_personal_api), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.cursor_help), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { clearConfirm = true }) { Text(stringResource(R.string.clear_data), color = MaterialTheme.colorScheme.error) }
                }
            }
            TextButton(onClick = { advanced = !advanced }) { Text(stringResource(if (advanced) R.string.hide_advanced else R.string.advanced)) }
            if (advanced) {
                Text(stringResource(R.string.model_details), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (apiVoice) ModelSetup(automatic)
                SettingsToggle(stringResource(R.string.api_vocab_title), stringResource(R.string.api_vocab_description), config?.personalVocabHintEnabled == true) { write { prefs.setPersonalVocabHintEnabled(it) } }
                if (BuildConfig.ENABLE_BENCHMARK) TextButton(onClick = onBenchmark) { Text(stringResource(R.string.debug_tools)) }
            }
        }
        }
    }
    if (clearConfirm) AlertDialog(
        onDismissRequest = { if (!clearing) clearConfirm = false }, title = { Text(stringResource(R.string.clear_data_question)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.clear_data_description))
                if (clearing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.clear_data_progress))
                }
            }
        },
        confirmButton = { TextButton(enabled = !clearing, onClick = {
            clearing = true
            scope.launch {
                val ok = runClearData {
                    context.getSystemService(ActivityManager::class.java).clearApplicationUserData()
                }
                clearing = false
                clearConfirm = false
                if (!ok) clearFailed = true
            }
        }) { Text(stringResource(if (clearing) R.string.clear_data_progress else R.string.clear_data)) } },
        dismissButton = { TextButton(enabled = !clearing, onClick = { clearConfirm = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (clearFailed) AlertDialog(
        onDismissRequest = { clearFailed = false },
        title = { Text(stringResource(R.string.clear_data_failed)) },
        text = { Text(stringResource(R.string.clear_data_failed_body)) },
        confirmButton = {
            TextButton(onClick = { clearFailed = false; openAppSettings(context) }) {
                Text(stringResource(R.string.open_app_settings))
            }
        },
        dismissButton = { TextButton(onClick = { clearFailed = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable private fun apiProviderName(id: String): String =
    if (id in setOf("custom", "openai-compatible")) stringResource(R.string.api_custom_name) else ApiCatalog.preset(id).name

private fun openAppLanguageSettings(context: Context) {
    val appLanguage = if (Build.VERSION.SDK_INT >= 33) {
        Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:${context.packageName}"))
    } else {
        Intent(Settings.ACTION_LOCALE_SETTINGS)
    }
    try {
        context.startActivity(appLanguage)
    } catch (_: Exception) {
        context.startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS))
    }
}

@Composable private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }
}
@Composable internal fun SettingsLink(title: String, description: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.semantics { role = Role.Button }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        }
    }
}
@Composable private fun ModeChoice(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable internal fun SettingsToggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked = checked, onCheckedChange = null)
    }
}
