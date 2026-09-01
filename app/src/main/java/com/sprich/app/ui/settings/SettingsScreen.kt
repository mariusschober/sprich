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
    val lidStatus by mm.lidStatus.collectAsState()
    val fastStatus by mm.fastConformerStatus.collectAsState()
    val nemotron560Status by mm.nemotron560Status.collectAsState()
    val nemotron160Status by mm.nemotron160Status.collectAsState()

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
            LanguageRow(lang, onSelect = { scope.launch{ prefs.setLanguage(it)} }, lidStatus = lidStatus)
            if (lang == Language.AUTO) {
                when (lidStatus) {
                    is ModelStatus.Ready -> Text("Automatic via Whisper Tiny LID (98M) → FastConformer 126M (per-utterance, RTF 0.038, 3× faster). Speak any of EN/DE/ES/FR without switching. Canary 180M remains Accurate.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    is ModelStatus.Downloading -> Text("Downloading Tiny LID… Automatic (winner FastConformer) will be available when Ready.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    else -> Text("Automatic is unavailable without Tiny LID (98M) + FastConformer 126M — dictation will not start in Automatic (fail-closed). Download both below or choose explicit EN/DE/ES/FR (Canary Accurate).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            ModelSection(
                engine = engine,
                canaryStatus = canaryStatus,
                lidStatus = lidStatus,
                fastStatus = fastStatus,
                nemotron560Status = nemotron560Status,
                nemotron160Status = nemotron160Status,
                onSelect = { scope.launch{ prefs.setEngine(EngineType.ACCURATE)} },
                onDownloadCanary = { scope.launch { try{ dm.downloadCanary() } catch (_:Exception){} } },
                onDownloadLid = { scope.launch { try{ dm.downloadLid() } catch (_:Exception){} } },
                onDownloadFast = { scope.launch { try{ dm.downloadFastConformer() } catch (_:Exception){} } },
                onDownloadNemotron560 = { scope.launch { try{ dm.downloadNemotron560() } catch (_:Exception){} } },
                onDownloadNemotron160 = { scope.launch { try{ dm.downloadNemotron160() } catch (_:Exception){} } },
                onDeleteCanary = { scope.launch { mm.deleteCanary() } },
                onDeleteLid = { scope.launch { mm.deleteLid() } },
                onDeleteFast = { scope.launch { mm.deleteFastConformer() } },
                onDeleteNemotron560 = { scope.launch { mm.deleteNemotron560() } },
                onDeleteNemotron160 = { scope.launch { mm.deleteNemotron160() } },
                onDeleteAllNemotron = { scope.launch { mm.deleteNemotron() } },
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
            SettingsRow("Clear local data", "", actionLabel = "Clear", onClick = { scope.launch{ prefs.clearAll(); mm.deleteCanary(); mm.deleteLid(); mm.deleteFastConformer(); mm.deleteNemotron() } })

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
@Composable private fun LanguageRow(current: Language, onSelect:(Language)->Unit, lidStatus: ModelStatus){
    Column {
        Text("Language", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        val showAuto = lidStatus is ModelStatus.Ready || lidStatus is ModelStatus.Downloading
        // Also allow Auto for future native Auto engines, but for now LID is the Auto mechanism
        val effectiveShowAuto = showAuto
        val options = buildList {
            if (effectiveShowAuto) add(Language.AUTO to "Automatic")
            add(Language.EN to "English"); add(Language.DE to "Deutsch"); add(Language.ES to "Español")
            add(Language.FR to "Français")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (lang, label) ->
                FilterChip(selected = current==lang, onClick = { onSelect(lang) }, label = { Text(label, style = MaterialTheme.typography.labelSmall) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer))
            }
        }
        if (!effectiveShowAuto) {
            Text("Pick the language you are speaking — Automatic requires Whisper Tiny LID (98M) + FastConformer 126M (winner, 3× faster than Canary) — download below.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (lidStatus is ModelStatus.Ready) {
            Text("Automatic via Whisper Tiny LID (98M) → FastConformer 126M (winner, RTF 0.038). No 30s cache. Canary 180M remains Accurate.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (lidStatus is ModelStatus.Downloading) {
            Text("Downloading Tiny LID… Automatic (FastConformer) will be ready shortly.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable private fun ModelSection(
    engine: EngineType,
    canaryStatus: ModelStatus,
    lidStatus: ModelStatus,
    fastStatus: ModelStatus,
    nemotron560Status: ModelStatus,
    nemotron160Status: ModelStatus,
    onSelect:(EngineType)->Unit,
    onDownloadCanary:()->Unit,
    onDownloadLid:()->Unit,
    onDownloadFast:()->Unit,
    onDownloadNemotron560:()->Unit,
    onDownloadNemotron160:()->Unit,
    onDeleteCanary:()->Unit,
    onDeleteLid:()->Unit,
    onDeleteFast:()->Unit,
    onDeleteNemotron560:()->Unit,
    onDeleteNemotron160:()->Unit,
    onDeleteAllNemotron:()->Unit,
    onCancel:()->Unit,
){
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)){
        Text("Speech model", style = MaterialTheme.typography.bodyMedium)
        ModelCardAdvanced("Canary 180M Flash", "198 MB · On-device (127M encoder + 71M decoder)", "Accurate explicit (EN/DE/ES/FR fixed). Not primary Auto — winner is FastConformer. Keep for explicit or fallback.", selected = engine==EngineType.ACCURATE, status = canaryStatus, onClick = { onSelect(EngineType.ACCURATE) }, onDownload = onDownloadCanary, onDelete = onDeleteCanary, onCancel = onCancel, totalMb = 198)
        ModelCardAdvanced("Whisper Tiny LID", "98 MB · On-device (12M encoder + 86M decoder)", "Winner Automatic: Tiny LID (winner with FastConformer, 3× faster). Enables per-utterance Auto, no 30s cache.", selected = false, status = lidStatus, onClick = {}, onDownload = onDownloadLid, onDelete = onDeleteLid, onCancel = onCancel, totalMb = 98)
        ModelCardAdvanced("FastConformer CTC — Automatic Winner", "126 MB · On-device (model.int8.onnx)", "Winner Automatic (with Tiny LID 98M, total 224M). 3× faster than Canary (419ms vs 1560ms), no accuracy penalty on 5-entry corpus.", selected = false, status = fastStatus, onClick = {}, onDownload = onDownloadFast, onDelete = onDeleteFast, onCancel = onCancel, totalMb = 126)
        ModelCardAdvanced("Nemotron 3.5 Streaming 560ms", "475 MB archive → ~500M extracted", "True streaming Auto per-stream (40 locales, `auto` strips tag). Accuracy-oriented. Independent of 160.", selected = false, status = nemotron560Status, onClick = {}, onDownload = onDownloadNemotron560, onDelete = onDeleteNemotron560, onCancel = onCancel, totalMb = 475)
        ModelCardAdvanced("Nemotron 3.5 Streaming 160ms", "475 MB archive → ~500M extracted", "True streaming Auto per-stream, low-latency (160ms chunk). Independent of 560.", selected = false, status = nemotron160Status, onClick = {}, onDownload = onDownloadNemotron160, onDelete = onDeleteNemotron160, onCancel = onCancel, totalMb = 475)
        if (nemotron560Status is ModelStatus.Ready || nemotron160Status is ModelStatus.Ready) {
            TextButton(onClick = onDeleteAllNemotron) { Text("Delete all Nemotron variants", style = MaterialTheme.typography.labelSmall) }
        }
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
