package com.sprich.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sprich.app.models.download.DownloadManager
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.models.manager.ModelStatus
import com.sprich.app.speech.api.EngineType
import com.sprich.app.speech.api.Language
import com.sprich.app.storage.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: ()->Unit, onBenchmark: ()->Unit, onVocab: ()->Unit = {}){
    val ctx = LocalContext.current
    val prefs = remember { Preferences(ctx) }
    val mm = remember { ModelManager(ctx) }
    val dm = remember { DownloadManager(ctx, mm) }
    val scope = rememberCoroutineScope()

    val instant by prefs.instantMode.collectAsState(initial = false)
    val lang by prefs.language.collectAsState(initial = Language.AUTO)
    val engine by prefs.engineType.collectAsState(initial = EngineType.ACCURATE)
    val haptics by prefs.haptics.collectAsState(initial = true)
    val commands by prefs.commands.collectAsState(initial = true)
    val canaryStatus by mm.canaryStatus.collectAsState()

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            CenterAlignedTopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(20.dp)) {

            Text("Dictation", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            SettingsToggle("Instant Dictation", "Start when a text field is focused", instant){ scope.launch{ prefs.setInstantMode(it)} }
            LanguageRow(lang){ scope.launch{ prefs.setLanguage(it)} }
            ModelSection(
                engine = engine,
                canaryStatus = canaryStatus,
                onSelect = { scope.launch{ prefs.setEngine(EngineType.ACCURATE)} },
                onDownloadCanary = { scope.launch { try{ dm.downloadCanary() } catch (_:Exception){} } },
                onDeleteCanary = { scope.launch { mm.deleteCanary() } },
                onCancel = { dm.cancel() }
            )

            HorizontalDivider()
            Text("Personalization", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            SettingsRow("Personal vocabulary", "Manage names & terms", onClick = onVocab)
            SettingsToggle("Learn my corrections", "Off by default", false){}

            HorizontalDivider()
            Text("Behavior", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            SettingsToggle("Haptic feedback", "On", haptics){ scope.launch{ prefs.setHaptics(it)} }
            SettingsToggle("Spoken editing", "On", commands){ scope.launch{ prefs.setCommands(it)} }
            SettingsRow("Stop after silence", "Automatic"){ }

            HorizontalDivider()
            BackupSttSection(prefs)
            HorizontalDivider()
            AiPolishSection(prefs)

            HorizontalDivider()
            Text("Privacy", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            SettingsRow("Audio storage", "Never"){}
            SettingsRow("Network use", "Models only"){}
            SettingsRow("Clear local data", "", actionLabel = "Clear", onClick = { scope.launch{ prefs.clearAll(); mm.deleteCanary() } })

            HorizontalDivider()
            Text("Advanced", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            SettingsRow("Current engine", "Canary 180M Flash INT8") {}
            SettingsRow("Benchmark", "", actionLabel = "Open", onClick = onBenchmark)
            SettingsRow("Diagnostics", ""){}
            SettingsRow("Open-source licenses", ""){}
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable private fun SettingsToggle(title:String, sub:String, checked:Boolean, onChecked:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable private fun SettingsRow(title:String, sub:String, actionLabel:String? = null, onClick:()->Unit = {}){
    // Whole row is tappable when there's no action button — otherwise onClick lives on the button only.
    val rowModifier = if (actionLabel == null) Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick() }
                      else Modifier.fillMaxWidth().padding(vertical = 4.dp)
    Row(rowModifier, horizontalArrangement = Arrangement.SpaceBetween){
        Column(Modifier.weight(1f)){
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (actionLabel != null) TextButton(onClick = onClick){ Text(actionLabel) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun LanguageRow(current: Language, onSelect:(Language)->Unit){
    Column {
        Text("Language", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Language.AUTO to "Automatic", Language.EN to "English", Language.DE to "Deutsch", Language.ES to "Español").forEach { (lang, label) ->
                FilterChip(selected = current==lang, onClick = { onSelect(lang) }, label = { Text(label, style = MaterialTheme.typography.labelSmall) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer))
            }
        }
    }
}

@Composable private fun ModelSection(
    engine: EngineType,
    canaryStatus: ModelStatus,
    onSelect:(EngineType)->Unit,
    onDownloadCanary:()->Unit,
    onDeleteCanary:()->Unit,
    onCancel:()->Unit,
){
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)){
        Text("Speech model", style = MaterialTheme.typography.bodyMedium)
        ModelCardAdvanced("Canary 180M Flash", "147 MB · On-device", "Primary accurate model. Optimized for your TCL T807D.", selected = engine==EngineType.ACCURATE, status = canaryStatus, onClick = { onSelect(EngineType.ACCURATE) }, onDownload = onDownloadCanary, onDelete = onDeleteCanary, onCancel = onCancel, totalMb = 147)
    }
}

@Composable private fun ModelCard(title:String, sub:String, desc:String, selected:Boolean, status: ModelStatus, onClick:()->Unit){
    ElevatedCard(
        onClick = onClick,
        colors = CardDefaults.elevatedCardColors(containerColor = if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ){
        Column(Modifier.padding(12.dp)){
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween){
                Column{
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                    Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (selected) Badge{ Text("Active", style = MaterialTheme.typography.labelSmall) }
                else when(status){
                    is ModelStatus.Downloading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    is ModelStatus.Ready -> Text("Ready", style=MaterialTheme.typography.labelSmall, color=MaterialTheme.colorScheme.primary)
                    else -> {}
                }
            }
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun ModelCardAdvanced(title:String, sub:String, desc:String, selected:Boolean, status: ModelStatus, onClick:()->Unit, onDownload:()->Unit, onDelete:()->Unit, onCancel:()->Unit, totalMb:Int = 198){
    ElevatedCard(
        onClick = onClick,
        colors = CardDefaults.elevatedCardColors(containerColor = if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ){
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)){
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween){
                Column{
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                    Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (selected) Badge{ Text("Active", style = MaterialTheme.typography.labelSmall) }
                else when(status){
                    is ModelStatus.Downloading -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)){
                        LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.width(60.dp).height(4.dp))
                        Text("${(status.progress*100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                    is ModelStatus.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)){
                        Text("Ready", style=MaterialTheme.typography.labelSmall, color=MaterialTheme.colorScheme.primary)
                        TextButton(onClick = onDelete){ Text("Delete", style=MaterialTheme.typography.labelSmall)}
                    }
                    is ModelStatus.Verifying -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    is ModelStatus.Failed -> Text(status.error.take(24), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    else -> TextButton(onClick = onDownload){ Text("Download", style=MaterialTheme.typography.labelSmall)}
                }
            }
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status is ModelStatus.Downloading) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)){
                    TextButton(onClick = onCancel){ Text("Cancel", style = MaterialTheme.typography.labelSmall)}
                    Text("${status.bytes/1024/1024} / ${if(status.total>0) status.total/1024/1024 else totalMb} MB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (status is ModelStatus.Failed) {
                TextButton(onClick = onDownload){ Text("Retry", style = MaterialTheme.typography.labelSmall)}
            }
        }
    }
}

@Composable
private fun BackupSttSection(prefs: Preferences) {
    val scope = rememberCoroutineScope()
    val modeRaw by prefs.sttModeRaw.collectAsState(initial = "local")
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("whisper-large-v3") }
    LaunchedEffect(Unit) {
        baseUrl = prefs.sttBaseUrl.first()
        apiKey = prefs.sttApiKey.first()
        model = prefs.sttModel.first().ifBlank { "whisper-large-v3" }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Backup speech-to-text", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text("OpenAI-compatible endpoint. Works with Grok (x.ai), Groq, Wizper via fal proxies, or any custom gateway.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = modeRaw == "local", onClick = { scope.launch { prefs.setSttMode(Preferences.SttMode.LOCAL) } }, label = { Text("Local only", style = MaterialTheme.typography.labelSmall) })
            FilterChip(selected = modeRaw == "fallback", onClick = { scope.launch { prefs.setSttMode(Preferences.SttMode.FALLBACK) } }, label = { Text("Local→Cloud fallback", style = MaterialTheme.typography.labelSmall) })
            FilterChip(selected = modeRaw == "remote", onClick = { scope.launch { prefs.setSttMode(Preferences.SttMode.REMOTE) } }, label = { Text("Cloud primary", style = MaterialTheme.typography.labelSmall) })
        }
        if (modeRaw != "local") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { baseUrl = "https://api.x.ai/v1"; scope.launch { prefs.setSttBaseUrl(baseUrl); prefs.setSttModel(model) } }, label = { Text("Grok x.ai", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { baseUrl = "https://api.groq.com/openai/v1"; model = "whisper-large-v3"; scope.launch { prefs.setSttBaseUrl(baseUrl); prefs.setSttModel(model) } }, label = { Text("Groq Whisper", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { scope.launch { prefs.setSttBaseUrl(baseUrl); prefs.setSttModel(model) } }, label = { Text("Custom / fal", style = MaterialTheme.typography.labelSmall) })
            }
            OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { TextButton(onClick = { scope.launch { prefs.setSttBaseUrl(baseUrl) } }) { Text("Save") } })
            OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API key") }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                trailingIcon = { TextButton(onClick = { scope.launch { prefs.setSttApiKey(apiKey) } }) { Text("Save") } })
            OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { TextButton(onClick = { scope.launch { prefs.setSttModel(model) } }) { Text("Save") } })
        }
    }
}

@Composable
private fun AiPolishSection(prefs: Preferences) {
    val scope = rememberCoroutineScope()
    val enabled by prefs.aiEnabled.collectAsState(initial = false)
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        baseUrl = prefs.aiBaseUrl.first()
        apiKey = prefs.aiApiKey.first()
        model = prefs.aiModel.first()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AI polish", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        SettingsToggle(
            "Fix grammar & punctuation with AI",
            "Sends transcript to a fast LLM after dictation. Off = fully on-device.",
            enabled,
        ) { scope.launch { prefs.setAiEnabled(it) } }
        if (enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai"; model = "gemini-2.0-flash-lite"; scope.launch { prefs.setAiBaseUrl(baseUrl); prefs.setAiModel(model) } }, label = { Text("Gemini fast", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { baseUrl = "https://api.openai.com/v1"; model = "gpt-4o-mini"; scope.launch { prefs.setAiBaseUrl(baseUrl); prefs.setAiModel(model) } }, label = { Text("GPT mini", style = MaterialTheme.typography.labelSmall) })
                AssistChip(onClick = { baseUrl = "https://api.x.ai/v1"; model = "grok-3-mini"; scope.launch { prefs.setAiBaseUrl(baseUrl); prefs.setAiModel(model) } }, label = { Text("Grok mini", style = MaterialTheme.typography.labelSmall) })
            }
            OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL (OpenAI-compatible)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { TextButton(onClick = { scope.launch { prefs.setAiBaseUrl(baseUrl) } }) { Text("Save") } })
            OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API key") }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                trailingIcon = { TextButton(onClick = { scope.launch { prefs.setAiApiKey(apiKey) } }) { Text("Save") } })
            OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { TextButton(onClick = { scope.launch { prefs.setAiModel(model) } }) { Text("Save") } })
        }
    }
}
