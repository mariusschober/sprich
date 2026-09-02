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
import com.sprich.app.core.audio.Pcm16Wav
import com.sprich.app.models.download.DownloadManager
import com.sprich.app.models.manager.ModelManager
import com.sprich.app.models.manager.ModelStatus
import com.sprich.app.speech.TranscriptionMode
import com.sprich.app.speech.api.EngineType
import com.sprich.app.speech.api.Language
import com.sprich.app.speech.refinement.RefinementMode
import com.sprich.app.storage.ApiSecretStore
import com.sprich.app.storage.LegacyApiCredentialMigrator
import com.sprich.app.storage.Preferences
import com.sprich.app.storage.SecretStoreResult
import kotlinx.coroutines.launch

// Centralized validation — delegate to EndpointValidator to avoid duplication
private fun isValidProductionHttpsUrl(url: String): Boolean = com.sprich.app.core.security.EndpointValidator.isValidHttpsUrl(url)

private fun sanitizedModelSummary(id: String): String = id.take(64)

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
    // Single derived readiness — Automatic requires BOTH Tiny LID and FastConformer (no Canary)
    val autoReady = lidStatus is ModelStatus.Ready && fastStatus is ModelStatus.Ready

    // One-time migration on entry
    LaunchedEffect(Unit) {
        try { LegacyApiCredentialMigrator.migrateIfNeeded(prefs, ApiSecretStore(ctx)) } catch (_: Exception) {}
    }

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
            SettingsToggle("Start automatically", "Begin listening when you tap a text field", instant){ scope.launch{ prefs.setInstantMode(it)} }
            LanguageRow(lang, onSelect = { scope.launch{ prefs.setLanguage(it)} }, lidStatus = lidStatus, fastStatus = fastStatus)
            if (lang == Language.AUTO) {
                when {
                    autoReady -> Text("Automatic — finds the language for you. Fast, on-device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    lidStatus is ModelStatus.Downloading || fastStatus is ModelStatus.Downloading -> Text("Downloading Automatic models… Will be ready when both complete.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    else -> {
                        val missing = buildList {
                            if (lidStatus !is ModelStatus.Ready) add("Language detector")
                            if (fastStatus !is ModelStatus.Ready) add("Fast transcription model")
                        }.joinToString(" + ")
                        Text("Automatic needs two on-device models. Missing: $missing", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (!autoReady) {
                    // Setup CTA — download missing
                    if (lidStatus !is ModelStatus.Ready && lidStatus !is ModelStatus.Downloading) {
                        TextButton(onClick = { scope.launch { try{ dm.downloadLid() } catch(_:Exception){} } }) { Text("Download language detector") }
                    }
                    if (fastStatus !is ModelStatus.Ready && fastStatus !is ModelStatus.Downloading) {
                        TextButton(onClick = { scope.launch { try{ dm.downloadFastConformer() } catch(_:Exception){} } }) { Text("Download Fast transcription model") }
                    }
                    if (lidStatus !is ModelStatus.Ready && fastStatus !is ModelStatus.Ready && lidStatus !is ModelStatus.Downloading && fastStatus !is ModelStatus.Downloading) {
                        TextButton(onClick = {
                            scope.launch {
                                try { dm.downloadLid() } catch(_:Exception){}
                                try { dm.downloadFastConformer() } catch(_:Exception){}
                            }
                        }) { Text("Set up Automatic (download both)") }
                    }
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
            TranscriptionSection(prefs)
            HorizontalDivider()
            RefinementSection(prefs)

            HorizontalDivider()
            DynamicPrivacySection(prefs)
            // Gesture legend — minimal 3-gesture release (no down newline)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Gestures", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text("Swipe left to delete • Right to undo • Swipe up outside bar to switch keyboard", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            var showClearConfirm by remember { mutableStateOf(false) }
            SettingsRow("Clear Sprich data", "Removes models, vocabulary, and keys. Cannot be undone.", actionLabel = "Clear", onClick = { showClearConfirm = true })
            if (showClearConfirm) {
                AlertDialog(
                    onDismissRequest = { showClearConfirm = false },
                    title = { Text("Clear Sprich data?") },
                    text = { Text("This removes downloaded models, personal vocabulary, provider keys and local diagnostics.") },
                    confirmButton = {
                        TextButton(onClick = {
                            showClearConfirm = false
                            scope.launch{
                                prefs.clearAll()
                                try { com.sprich.app.storage.ApiSecretStore(ctx).clearAll() } catch (_:Exception){}
                                try { com.sprich.app.diagnostics.ReplayHarness.clearAll(ctx) } catch (_:Exception){}
                                try { java.io.File(ctx.filesDir, "diagnostics").deleteRecursively(); java.io.File(ctx.noBackupFilesDir, "diagnostics").deleteRecursively(); java.io.File(ctx.filesDir, "benchmark").deleteRecursively(); java.io.File(ctx.noBackupFilesDir, "benchmark").deleteRecursively() } catch (_:Exception){}
                                mm.deleteCanary(); mm.deleteLid(); mm.deleteFastConformer(); mm.deleteNemotron()
                            }
                        }) { Text("Clear data") }
                    },
                    dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") } }
                )
            }

            HorizontalDivider()
            Text("Advanced", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            val transcriptionLabel = when {
                lang == Language.AUTO && autoReady -> "Currently using — Automatic"
                lang == Language.AUTO && !autoReady -> "Currently using — Automatic (add models)"
                canaryStatus is ModelStatus.Ready -> "Currently using — ${lang.code.uppercase()}"
                else -> "Currently using — ${lang.code.uppercase()} (add model)"
            }
            val transcriptionDetail = when {
                lang == Language.AUTO && autoReady -> "On-device · 224 MB"
                lang == Language.AUTO -> "Add models to enable Automatic"
                else -> if (canaryStatus is ModelStatus.Ready) "On-device · Ready" else "Add Accurate model"
            }
            SettingsRow(transcriptionLabel, transcriptionDetail) {}
            // Advanced rows — only benchmark/diagnostics/licenses, model size details hidden here
            val isDebugBenchmark = try { com.sprich.app.BuildConfig.ENABLE_BENCHMARK } catch (_: Exception) { false }
            if (isDebugBenchmark) {
                SettingsRow("Benchmark", "", actionLabel = "Open", onClick = onBenchmark)
            }
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
@Composable private fun LanguageRow(current: Language, onSelect:(Language)->Unit, lidStatus: ModelStatus, fastStatus: ModelStatus){
    Column {
        Text("Language", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        val autoReadyRow = lidStatus is ModelStatus.Ready && fastStatus is ModelStatus.Ready
        val showDownloading = lidStatus is ModelStatus.Downloading || fastStatus is ModelStatus.Downloading
        // Show Automatic always to allow setup — but indicate readiness; hide only if never downloadable? Keep visible.
        val effectiveShowAuto = true
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
        when {
            autoReadyRow -> Text("Automatic — finds the language for you. Fast, on-device.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            showDownloading -> Text("Downloading Automatic models…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            else -> Text("Automatic needs two models — add them below.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
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
        Text("Automatic finds the language for you after you add its models (224 MB total).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ModelCardAdvanced("Accurate — manual language", "198 MB · On-device", "Higher accuracy when you choose the language.", selected = engine==EngineType.ACCURATE, status = canaryStatus, onClick = { onSelect(EngineType.ACCURATE) }, onDownload = onDownloadCanary, onDelete = onDeleteCanary, onCancel = onCancel, totalMb = 198)
        ModelCardAdvanced("Language detection", "98 MB · On-device", "Needed for Automatic.", selected = false, status = lidStatus, onClick = {}, onDownload = onDownloadLid, onDelete = onDeleteLid, onCancel = onCancel, totalMb = 98)
        ModelCardAdvanced("Fast transcription", "126 MB · On-device", "Fast on-device transcription for Automatic.", selected = false, status = fastStatus, onClick = {}, onDownload = onDownloadFast, onDelete = onDeleteFast, onCancel = onCancel, totalMb = 126)
        val isDebugModels = try { com.sprich.app.BuildConfig.ENABLE_BENCHMARK } catch (_: Exception) { false }
        if (isDebugModels) {
            ModelCardAdvanced("Streaming (experimental) — 560ms", "475 MB · Experimental", "Not needed for everyday use.", selected = false, status = nemotron560Status, onClick = {}, onDownload = onDownloadNemotron560, onDelete = onDeleteNemotron560, onCancel = onCancel, totalMb = 475)
            ModelCardAdvanced("Streaming (experimental) — 160ms", "475 MB · Experimental", "Not needed for everyday use.", selected = false, status = nemotron160Status, onClick = {}, onDownload = onDownloadNemotron160, onDelete = onDeleteNemotron160, onCancel = onCancel, totalMb = 475)
            if (nemotron560Status is ModelStatus.Ready || nemotron160Status is ModelStatus.Ready) {
                TextButton(onClick = onDeleteAllNemotron) { Text("Delete all Nemotron variants", style = MaterialTheme.typography.labelSmall) }
            }
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
private fun TranscriptionSection(prefs: Preferences) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mode by prefs.transcriptionMode.collectAsState(initial = TranscriptionMode.ON_DEVICE)
    val providerId by prefs.sttProviderId.collectAsState(initial = "meta-muse-voice-transcribe")
    val baseUrl by prefs.sttBaseUrl.collectAsState(initial = "")
    val model by prefs.sttModel.collectAsState(initial = "whisper-large-v3")
    val streamingEnabled by prefs.sttStreamingEnabled.collectAsState(initial = true)
    val secretStore = remember { ApiSecretStore(ctx) }
    // Shared pooled client for Settings Test — same pooling semantics as production, redirects disabled for credentialed BYOK
    val sharedClient = remember { okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build() }
    var hasKey by remember { mutableStateOf(false) }
    var hasMuseKey by remember { mutableStateOf(false) }
    var hasGeminiKey by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var showKeyEntry by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val isMuse = providerId == "meta-muse-voice-transcribe" || providerId == "meta-muse"
    val isGemini = providerId == "gemini" || providerId.startsWith("gemini-")
    val isCustom = providerId == "openai-compatible" || providerId == "custom"
    // hasKey = decryptable secure credential only — migration has already moved legacy if present
    LaunchedEffect(Unit) {
        try { LegacyApiCredentialMigrator.migrateIfNeeded(prefs, secretStore) } catch (_: Exception) {}
        hasKey = try { secretStore.hasSecret("stt_default") } catch (_: Exception) { false }
        hasMuseKey = hasKey // single key for Muse (stt_default)
        hasGeminiKey = hasKey
    }
    LaunchedEffect(providerId, hasKey) {
        // Refresh hasKey when provider changes — for locked providers, check single key
        hasKey = try { secretStore.hasSecret("stt_default") } catch (_: Exception) { false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Transcription", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text("On-device is default. Add an API key and enable API to make API primary with on-device fallback.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == TranscriptionMode.ON_DEVICE, onClick = { scope.launch { prefs.setTranscriptionMode(TranscriptionMode.ON_DEVICE) } }, label = { Text("On-device", style = MaterialTheme.typography.labelSmall) })
            FilterChip(selected = mode == TranscriptionMode.API_PRIMARY, onClick = { scope.launch { prefs.setTranscriptionMode(TranscriptionMode.API_PRIMARY) } }, label = { Text("API → On-device fallback", style = MaterialTheme.typography.labelSmall) })
            FilterChip(selected = mode == TranscriptionMode.LOCAL_API_FALLBACK, onClick = { scope.launch { prefs.setTranscriptionMode(TranscriptionMode.LOCAL_API_FALLBACK) } }, label = { Text("On-device → API fallback", style = MaterialTheme.typography.labelSmall) })
        }
        if (mode != TranscriptionMode.ON_DEVICE) {
            Text("Audio is sent directly from your device to your selected transcription provider using your API key. Sprich does not provide, proxy or receive your API key. Usage is billed directly by your provider. On-device stays as fallback if API fails.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = isMuse, onClick = { scope.launch { prefs.setSttProviderId("meta-muse-voice-transcribe") } }, label = { Text("Muse Voice (Default)", style = MaterialTheme.typography.labelSmall) })
                FilterChip(selected = isGemini, onClick = { scope.launch { prefs.setSttProviderId("gemini") } }, label = { Text("Gemini", style = MaterialTheme.typography.labelSmall) })
                FilterChip(selected = isCustom, onClick = { scope.launch { prefs.setSttProviderId("custom") } }, label = { Text("Custom", style = MaterialTheme.typography.labelSmall) })
            }
            if (isMuse) {
                Text("Muse Voice Transcribe — locked: https://api.meta.ai, model muse-voice-transcribe-1.0, single key for STT + text. Streaming default.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            } else if (isGemini) {
                Text("Gemini 3.5 Transcribe — locked: generativelanguage.googleapis.com, model gemini-3.5-transcribe. Single key.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            // Streaming removed for this release — ships verified frozen-PCM batch only (7.4)
            // Streaming capability=false until true live streaming proven; hide toggle and Realtime copy
            if (false && (isMuse || isGemini)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Streaming", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = streamingEnabled, onCheckedChange = { scope.launch { prefs.setSttStreamingEnabled(it) } })
                    Text(if (streamingEnabled) "Realtime (default)" else "Batch", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Streaming: 80ms realtime via WebSocket (ENDPOINTING). Batch: single POST. Toggle as needed.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (isMuse || isGemini) {
                Text("Batch transcription only — streaming not yet available in this release. Audio is sent as one utterance after you stop speaking.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // API key: single for Muse/Gemini (same key for STT+refinement), separate for Custom
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (isMuse || isGemini) "API key (single for STT + text)" else "API key", style = MaterialTheme.typography.bodyMedium)
                if (hasKey) {
                    AssistChip(onClick = { showKeyEntry = true; keyInput = ""; saveError = null }, label = { Text("Saved — Replace") })
                    TextButton(onClick = {
                        scope.launch {
                            secretStore.removeSecret("stt_default")
                            // For Muse/Gemini single key, also clear refine_default if it was twin-written
                            try { secretStore.removeSecret("refine_default") } catch (_: Exception) {}
                            hasKey = false; testResult = "Key removed"
                        }
                    }) { Text("Remove") }
                } else {
                    AssistChip(onClick = { showKeyEntry = true; saveError = null }, label = { Text("Add") })
                    Text("Not set", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (isMuse) Text("Muse uses one Meta API key for both Voice Transcribe (STT) and Spark (text). Paste your key from dev.meta.ai.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (isGemini) Text("Gemini uses one Google API key for both Transcribe and text. Paste from ai.google.dev.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!hasKey && !showKeyEntry) {
                Text("API key needs to be entered again if previously saved but no longer decryptable.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (saveError != null) {
                Text(saveError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (showKeyEntry) {
                var reveal by remember { mutableStateOf(false) }
                OutlinedTextField(value = keyInput, onValueChange = { keyInput = it; saveError = null }, label = { Text("API key") }, singleLine = true,
                    visualTransformation = if (reveal) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        Row {
                            TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") }
                            TextButton(onClick = {
                                scope.launch {
                                    val trimmed = keyInput.trim()
                                    if (trimmed.isBlank()) { saveError = "Key cannot be empty"; return@launch }
                                    // Single key for Muse/Gemini: twin-write to both refs so refinement reuses same key
                                    val res = secretStore.saveSecret("stt_default", trimmed)
                                    val res2 = if (isMuse || isGemini) secretStore.saveSecret("refine_default", trimmed) else SecretStoreResult.Success
                                    when {
                                        res is SecretStoreResult.Success && res2 is SecretStoreResult.Success -> {
                                            prefs.setSttCredentialRef("stt_default")
                                            if (isMuse || isGemini) prefs.setRefinementCredentialRef("stt_default")
                                            keyInput = ""
                                            showKeyEntry = false
                                            hasKey = true
                                            testResult = "Saved — key stored securely. Choose Online provider explicitly to enable cloud."
                                            saveError = null
                                            // 7.7: saving a key ≠ permission to start sending speech online — do NOT auto-switch to API_PRIMARY
                                        }
                                        else -> {
                                            saveError = "Could not securely save API key"
                                            hasKey = false
                                        }
                                    }
                                }
                            }) { Text("Save") }
                        }
                    }, modifier = Modifier.fillMaxWidth())
                if (saveError != null) Text("Could not securely save API key — try again, or clear app data if keystore is corrupted.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            // Locked Muse/Gemini: show fixed endpoint/model, hide editable fields
            if (isMuse) {
                Text("Endpoint: https://api.meta.ai/v1 (locked) • Model: muse-voice-transcribe-1.0 (locked)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (isGemini) {
                Text("Endpoint: https://generativelanguage.googleapis.com (locked) • Model: gemini-3.5-transcribe (locked)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isCustom) {
                var editBaseUrl by remember { mutableStateOf(baseUrl) }
                var editModel by remember { mutableStateOf(model) }
                LaunchedEffect(baseUrl) { editBaseUrl = baseUrl }
                LaunchedEffect(model) { editModel = model }
                OutlinedTextField(value = editBaseUrl, onValueChange = { editBaseUrl = it; testResult = null }, label = { Text("Base URL (https://)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { if (editBaseUrl.isNotBlank() && !isValidProductionHttpsUrl(editBaseUrl)) Text("Must be https:// (http only allowed for localhost in debug)", color = MaterialTheme.colorScheme.error) },
                    trailingIcon = {
                        TextButton(onClick = {
                            scope.launch {
                                if (!isValidProductionHttpsUrl(editBaseUrl)) { testResult = "Invalid URL — must be https://"; return@launch }
                                prefs.setSttBaseUrl(editBaseUrl)
                                testResult = "Saved"
                            }
                        }) { Text("Save") }
                    })
                OutlinedTextField(value = editModel, onValueChange = { editModel = it }, label = { Text("Model ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { TextButton(onClick = { scope.launch { prefs.setSttModel(editModel); testResult = "Saved" } }) { Text("Save") } })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    scope.launch {
                        testResult = "Testing…"
                        try {
                            val cred = secretStore.loadSecret("stt_default")
                            if (cred.isNullOrBlank()) { testResult = "Missing API key — add key first"; return@launch }
                            val t0 = System.currentTimeMillis()
                            val pcm = try {
                                ctx.assets.open("jfk.wav").use { Pcm16Wav.read(it).samples }
                            } catch (_: Exception) {
                                ShortArray(16000) { (kotlin.math.sin(it * 0.01) * 8000).toInt().toShort() }
                            }
                            val trimmedPcm = if (pcm.size > 16000*8) pcm.copyOfRange(0, 16000*8) else pcm
                            // Use single factory — same path as production (required 7.1)
                            val provider: com.sprich.app.speech.remote.RemoteSttProvider = run {
                                val cfg = com.sprich.app.speech.remote.RemoteSttConfig(
                                    providerId = when { isMuse -> "meta-muse-voice-transcribe"; isGemini -> "gemini"; else -> "openai-compatible" },
                                    endpoint = when { isMuse -> com.sprich.app.storage.MuseDefaults.BASE_URL; isGemini -> com.sprich.app.storage.GeminiDefaults.BASE_URL; else -> baseUrl },
                                    model = when { isMuse -> com.sprich.app.storage.MuseDefaults.MODEL; isGemini -> com.sprich.app.storage.GeminiDefaults.MODEL; else -> model },
                                    languagePolicy = com.sprich.app.speech.LanguagePolicy.Automatic,
                                    deadlineMs = 3500L,
                                    credentialRef = "stt_default",
                                    supportsStreaming = false,
                                    preferStreaming = false,
                                )
                                if (!isMuse && !isGemini) {
                                    if (!isValidProductionHttpsUrl(baseUrl)) { testResult = "Invalid URL — must be https://"; return@launch }
                                    if (model.isBlank()) { testResult = "Missing model"; return@launch }
                                }
                                com.sprich.app.speech.remote.RemoteProviderFactory.create(cfg, sharedClient)
                            }
                            val req = com.sprich.app.speech.remote.RemoteSttRequest(trimmedPcm, 16000, com.sprich.app.speech.LanguagePolicy.Automatic, emptyList(), System.nanoTime(), cred, streamingEnabled)
                            val res = provider.transcribe(req)
                            val dt = System.currentTimeMillis() - t0
                            if (res.text.isBlank()) { testResult = "Connected but returned blank — check model/audio"; return@launch }
                            val preview = res.text.take(40).replace("\n"," ")
                            testResult = "Connected · ${dt} ms · $preview"
                        } catch (e: Exception) {
                            val failure = com.sprich.app.speech.remote.ApiFailure.fromException(e)
                            val typed = (e as? com.sprich.app.speech.remote.RemoteSttException)?.failure ?: failure
                            testResult = typed.toDisplay() + " · " + (e.message?.take(60) ?: "")
                        }
                    }
                }) { Text("Test") }
                if (testResult != null) Text(testResult!!, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RefinementSection(prefs: Preferences) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mode by prefs.refinementMode.collectAsState(initial = RefinementMode.OFF)
    val sttProviderId by prefs.sttProviderId.collectAsState(initial = "meta-muse-voice-transcribe")
    val isMuseStt = sttProviderId == "meta-muse-voice-transcribe" || sttProviderId == "meta-muse"
    val isGeminiStt = sttProviderId == "gemini" || sttProviderId.startsWith("gemini-")
    val baseUrl by prefs.refinementBaseUrl.collectAsState(initial = "")
    val model by prefs.refinementModel.collectAsState(initial = "")
    val secretStore = remember { ApiSecretStore(ctx) }
    val sharedClient = remember { okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build() }
    var hasKey by remember { mutableStateOf(false) }
    var hasSttKey by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var showKeyEntry by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { LegacyApiCredentialMigrator.migrateIfNeeded(prefs, secretStore) } catch (_: Exception) {}
        hasKey = try { secretStore.hasSecret("refine_default") } catch (_: Exception) { false }
        hasSttKey = try { secretStore.hasSecret("stt_default") } catch (_: Exception) { false }
    }
    LaunchedEffect(sttProviderId) {
        hasSttKey = try { secretStore.hasSecret("stt_default") } catch (_: Exception) { false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Improve transcript", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == RefinementMode.OFF, onClick = { scope.launch { prefs.setRefinementMode(RefinementMode.OFF) } }, label = { Text("Off", style = MaterialTheme.typography.labelSmall) })
            FilterChip(selected = mode == RefinementMode.CORRECT, onClick = { scope.launch { prefs.setRefinementMode(RefinementMode.CORRECT) } }, label = { Text("Correct", style = MaterialTheme.typography.labelSmall) })
            FilterChip(selected = mode == RefinementMode.CLEAN_DICTATION, onClick = { scope.launch { prefs.setRefinementMode(RefinementMode.CLEAN_DICTATION) } }, label = { Text("Clean dictation", style = MaterialTheme.typography.labelSmall) })
        }
        Text(when (mode) {
            RefinementMode.CORRECT -> "Fixes grammar, punctuation and obvious transcription errors while preserving your wording."
            RefinementMode.CLEAN_DICTATION -> "Also removes obvious fillers and false starts so natural speech reads like written text."
            else -> "Off = fully on-device."
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (mode != RefinementMode.OFF) {
            Text("Transcript text is sent directly from your device to your selected refinement provider using your API key. Sprich does not provide, proxy or receive your API key.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Single key for Muse/Gemini: reuse STT key
            if (isMuseStt || isGeminiStt) {
                Text(
                    if (isMuseStt) "Uses your Muse API key from Transcription (single key for STT + text). No separate key needed."
                    else "Uses your Gemini API key from Transcription (single key).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Refinement key", style = MaterialTheme.typography.bodyMedium)
                    if (hasSttKey) {
                        AssistChip(onClick = {}, enabled = false, label = { Text("Using Transcription key ✓") })
                    } else {
                        AssistChip(onClick = {}, enabled = false, label = { Text("Add key in Transcription above") })
                        Text("Not set", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (isMuseStt) Text("Muse refinement: model muse-spark-1.1 via https://api.meta.ai (locked)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Text("Gemini refinement: model gemini-2.0-flash via generativelanguage.googleapis.com (locked)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        scope.launch {
                            testResult = "Testing…"
                            val cannedInput = "tomorrow i think we should meet at nine"
                            try {
                                val secret = secretStore.loadSecret("stt_default")
                                if (secret.isNullOrBlank()) { testResult = "Missing API key — add Muse/Gemini key in Transcription"; return@launch }
                                val t0 = System.currentTimeMillis()
                                val prov = if (isMuseStt) {
                                    com.sprich.app.ai.OpenAiCompatibleRefinementProvider(
                                        com.sprich.app.storage.MuseRefinementDefaults.ENDPOINT,
                                        com.sprich.app.storage.MuseRefinementDefaults.MODEL,
                                        secret, sharedClient
                                    )
                                } else {
                                    com.sprich.app.ai.OpenAiCompatibleRefinementProvider(
                                        com.sprich.app.storage.GeminiRefinementDefaults.ENDPOINT,
                                        com.sprich.app.storage.GeminiRefinementDefaults.MODEL,
                                        secret, sharedClient
                                    )
                                }
                                val res = prov.refine(com.sprich.app.speech.refinement.RefinementRequest(cannedInput, "en", mode))
                                val ms = System.currentTimeMillis() - t0
                                testResult = "OK · ${ms}ms · ${res.text.take(40).replace("\n"," ")}"
                            } catch (e: Exception) {
                                testResult = "Failed: ${e.message?.take(80)}"
                            }
                        }
                    }) { Text("Test") }
                    if (testResult != null) Text(testResult!!, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                // Custom / OpenAI-compatible: separate key
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("API key", style = MaterialTheme.typography.bodyMedium)
                    if (hasKey) {
                        AssistChip(onClick = { showKeyEntry = true; keyInput = ""; saveError = null }, label = { Text("Saved — Replace") })
                        TextButton(onClick = { scope.launch { secretStore.removeSecret("refine_default"); hasKey = false; testResult = "Key removed" } }) { Text("Remove") }
                    } else {
                        AssistChip(onClick = { showKeyEntry = true; saveError = null }, label = { Text("Add") })
                        Text("Not set", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (!hasKey && !showKeyEntry) {
                    Text("API key needs to be entered again if previously saved but no longer decryptable.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (saveError != null) Text(saveError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (showKeyEntry) {
                    var reveal by remember { mutableStateOf(false) }
                    OutlinedTextField(value = keyInput, onValueChange = { keyInput = it; saveError = null }, label = { Text("API key") }, singleLine = true,
                        visualTransformation = if (reveal) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            Row {
                                TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") }
                                TextButton(onClick = {
                                    scope.launch {
                                        val trimmed = keyInput.trim()
                                        if (trimmed.isBlank()) { saveError = "Key cannot be empty"; return@launch }
                                        val res = secretStore.saveSecret("refine_default", trimmed)
                                        when (res) {
                                            is SecretStoreResult.Success -> {
                                                prefs.setRefinementCredentialRef("refine_default")
                                                keyInput = ""; showKeyEntry = false; hasKey = true; testResult = "Saved"; saveError = null
                                            }
                                            is SecretStoreResult.Failure -> { saveError = "Could not securely save API key"; hasKey = false }
                                        }
                                    }
                                }) { Text("Save") }
                            }
                        }, modifier = Modifier.fillMaxWidth())
                }
                var editBaseUrl by remember { mutableStateOf(baseUrl) }
                var editModel by remember { mutableStateOf(model) }
                LaunchedEffect(baseUrl) { editBaseUrl = baseUrl }
                LaunchedEffect(model) { editModel = model }
                OutlinedTextField(value = editBaseUrl, onValueChange = { editBaseUrl = it }, label = { Text("Base URL (OpenAI-compatible, https://)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { if (editBaseUrl.isNotBlank() && !isValidProductionHttpsUrl(editBaseUrl)) Text("Must be https://", color = MaterialTheme.colorScheme.error) },
                    trailingIcon = { TextButton(onClick = {
                        scope.launch {
                            if (editBaseUrl.isNotBlank() && !isValidProductionHttpsUrl(editBaseUrl)) { testResult = "Invalid URL — must be https://"; return@launch }
                            prefs.setRefinementBaseUrl(editBaseUrl); testResult = "Saved"
                        }
                    }) { Text("Save") } })
                OutlinedTextField(value = editModel, onValueChange = { editModel = it }, label = { Text("Model ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { TextButton(onClick = { scope.launch { prefs.setRefinementModel(editModel); testResult = "Saved" } }) { Text("Save") } })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        scope.launch {
                            testResult = "Testing…"
                            val cannedInput = "tomorrow i think we should meet at nine"
                            try {
                                if (editBaseUrl.isNotBlank() && !isValidProductionHttpsUrl(editBaseUrl)) { testResult = "Invalid URL — must be https://"; return@launch }
                                val secret = secretStore.loadSecret("refine_default")
                                if (secret.isNullOrBlank()) { testResult = "Missing API key — add key first"; return@launch }
                                val t0 = System.currentTimeMillis()
                                val prov = com.sprich.app.ai.OpenAiCompatibleRefinementProvider(editBaseUrl, editModel, secret, sharedClient)
                                val res = prov.refine(com.sprich.app.speech.refinement.RefinementRequest(cannedInput, "en", mode))
                                val ms = System.currentTimeMillis() - t0
                                testResult = "OK · ${ms}ms · ${res.text.take(40).replace("\n"," ")}"
                            } catch (e: Exception) {
                                testResult = "Failed: ${e.message?.take(80)}"
                            }
                        }
                    }) { Text("Test") }
                    if (testResult != null) Text(testResult!!, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DynamicPrivacySection(prefs: Preferences) {
    val mode by prefs.transcriptionMode.collectAsState(initial = TranscriptionMode.ON_DEVICE)
    val refine by prefs.refinementMode.collectAsState(initial = RefinementMode.OFF)
    val debugWav by prefs.debugWavCapture.collectAsState(initial = false)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Privacy", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        when {
            mode == TranscriptionMode.ON_DEVICE && refine == RefinementMode.OFF ->
                Text("Speech and transcript stay on this device. Network is used only for model downloads.", style = MaterialTheme.typography.bodySmall)
            mode != TranscriptionMode.ON_DEVICE && refine != RefinementMode.OFF ->
                Text("Audio is sent directly from your device to your selected transcription provider using your API key. Transcript text is sent directly to your refinement provider. Sprich does not provide, proxy or receive your API key. API usage is billed directly by your selected provider.", style = MaterialTheme.typography.bodySmall)
            mode != TranscriptionMode.ON_DEVICE ->
                Text("Audio is sent directly from your device to your selected transcription provider using your API key. Sprich does not provide, proxy or receive your API key. API usage is billed directly by your provider.", style = MaterialTheme.typography.bodySmall)
            refine != RefinementMode.OFF ->
                Text("Transcript text is sent directly from your device to your selected refinement provider using your API key. Sprich does not provide, proxy or receive your API key.", style = MaterialTheme.typography.bodySmall)
        }
        if (debugWav) {
            Text("Debug capture enabled: test audio is stored locally (WAV). Disable after testing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        } else {
            Text("Audio storage: Never — audio is not retained after transcription", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Network use: ${when { mode == TranscriptionMode.ON_DEVICE && refine == RefinementMode.OFF -> "Models only"; mode != TranscriptionMode.ON_DEVICE && refine != RefinementMode.OFF -> "Models + STT + refinement"; mode != TranscriptionMode.ON_DEVICE -> "Models + STT"; else -> "Models + refinement" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// Deprecated wrappers for backward compatibility — delegate to new sections
@Composable
private fun BackupSttSection(prefs: Preferences) { TranscriptionSection(prefs) }
@Composable
private fun AiPolishSection(prefs: Preferences) { RefinementSection(prefs) }
