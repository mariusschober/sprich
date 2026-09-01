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

// Shared validation: production custom endpoints must require https:// (except debug localhost via BuildConfig.DEBUG)
private fun isValidProductionHttpsUrl(url: String): Boolean {
    if (url.isBlank()) return false
    return try {
        val uri = java.net.URI(url.trim())
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "https") {
            // Allow http only for debug MockWebServer localhost
            if (scheme == "http") {
                val host = uri.host?.lowercase() ?: ""
                // Strict allow only localhost/127.0.0.1 in debug builds
                val isDebug = try { com.sprich.app.BuildConfig.DEBUG } catch (_: Exception) { false }
                if (!isDebug) return false
                if (host != "localhost" && host != "127.0.0.1" && host != "10.0.2.2") return false
            } else return false
        }
        val host = uri.host ?: return false
        if (host.isBlank()) return false
        if (uri.userInfo != null) return false // embedded userinfo credentials not allowed
        // reject query-token URLs? For now allow but don't log
        true
    } catch (_: Exception) { false }
}

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
            SettingsToggle("Instant Dictation", "Start when a text field is focused", instant){ scope.launch{ prefs.setInstantMode(it)} }
            LanguageRow(lang, onSelect = { scope.launch{ prefs.setLanguage(it)} }, lidStatus = lidStatus, fastStatus = fastStatus)
            if (lang == Language.AUTO) {
                when {
                    autoReady -> Text("Automatic language — Detects English, German, Spanish and French automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    lidStatus is ModelStatus.Downloading || fastStatus is ModelStatus.Downloading -> Text("Downloading Automatic models… Will be ready when both complete.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    else -> {
                        val missing = buildList {
                            if (lidStatus !is ModelStatus.Ready) add("Language detector")
                            if (fastStatus !is ModelStatus.Ready) add("Fast transcription model")
                        }.joinToString(" + ")
                        Text("Automatic — Requires two on-device models: Language detector + Fast transcription model. Missing: $missing", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
            SettingsRow("Clear local data", "", actionLabel = "Clear", onClick = { scope.launch{ prefs.clearAll(); try { com.sprich.app.storage.ApiSecretStore(ctx).clearAll() } catch (_:Exception){}; mm.deleteCanary(); mm.deleteLid(); mm.deleteFastConformer(); mm.deleteNemotron() } })

            HorizontalDivider()
            Text("Advanced", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            val transcriptionLabel = when {
                lang == Language.AUTO && autoReady -> "Current transcription — Automatic · Fast on-device"
                lang == Language.AUTO && !autoReady -> "Current transcription — Automatic unavailable (missing models)"
                canaryStatus is ModelStatus.Ready -> "Current transcription — Accurate · ${lang.code.uppercase()}"
                else -> "Current transcription — Accurate · ${lang.code.uppercase()} (model not Ready)"
            }
            val transcriptionDetail = when {
                lang == Language.AUTO && autoReady -> "Language ID: Whisper Tiny · ASR: FastConformer CTC · 224 MB total"
                lang == Language.AUTO -> "Requires Tiny LID + FastConformer"
                else -> "ASR: Canary 180M Flash INT8 · ${if (canaryStatus is ModelStatus.Ready) "Ready" else "Not downloaded"}"
            }
            SettingsRow(transcriptionLabel, transcriptionDetail) {}
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
            autoReadyRow -> Text("Automatic language — Detects English, German, Spanish and French automatically. Fast on-device.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            showDownloading -> Text("Downloading Automatic models…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            else -> Text("Automatic requires two models: Language detector + Fast transcription model — download below.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
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
        Text("Automatic language — Detects English, German, Spanish and French automatically. 224 MB total (98 + 126).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ModelCardAdvanced("Canary 180M Flash", "198 MB · On-device", "Accurate — Choose language manually for the accurate model.", selected = engine==EngineType.ACCURATE, status = canaryStatus, onClick = { onSelect(EngineType.ACCURATE) }, onDownload = onDownloadCanary, onDelete = onDeleteCanary, onCancel = onCancel, totalMb = 198)
        ModelCardAdvanced("Whisper Tiny — Language detector", "98 MB · On-device", "Automatic language detection.", selected = false, status = lidStatus, onClick = {}, onDownload = onDownloadLid, onDelete = onDeleteLid, onCancel = onCancel, totalMb = 98)
        ModelCardAdvanced("FastConformer CTC", "126 MB · On-device", "Automatic — Fast on-device transcription.", selected = false, status = fastStatus, onClick = {}, onDownload = onDownloadFast, onDelete = onDeleteFast, onCancel = onCancel, totalMb = 126)
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
private fun TranscriptionSection(prefs: Preferences) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mode by prefs.transcriptionMode.collectAsState(initial = TranscriptionMode.ON_DEVICE)
    val providerId by prefs.sttProviderId.collectAsState(initial = "openai-compatible")
    val baseUrl by prefs.sttBaseUrl.collectAsState(initial = "")
    val model by prefs.sttModel.collectAsState(initial = "whisper-large-v3")
    val secretStore = remember { ApiSecretStore(ctx) }
    // Shared pooled client for Settings Test — same pooling semantics as production
    val sharedClient = remember { okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build() }
    var hasKey by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var showKeyEntry by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    // hasKey = decryptable secure credential only — migration has already moved legacy if present
    LaunchedEffect(Unit) {
        try { LegacyApiCredentialMigrator.migrateIfNeeded(prefs, secretStore) } catch (_: Exception) {}
        hasKey = try { secretStore.hasSecret("stt_default") } catch (_: Exception) { false }
    }
    // Refresh when secret changes
    LaunchedEffect(hasKey) {}
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Transcription", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == TranscriptionMode.ON_DEVICE, onClick = { scope.launch { prefs.setTranscriptionMode(TranscriptionMode.ON_DEVICE) } }, label = { Text("On-device", style = MaterialTheme.typography.labelSmall) })
            FilterChip(selected = mode == TranscriptionMode.API_PRIMARY, onClick = { scope.launch { prefs.setTranscriptionMode(TranscriptionMode.API_PRIMARY) } }, label = { Text("API", style = MaterialTheme.typography.labelSmall) })
            FilterChip(selected = mode == TranscriptionMode.LOCAL_API_FALLBACK, onClick = { scope.launch { prefs.setTranscriptionMode(TranscriptionMode.LOCAL_API_FALLBACK) } }, label = { Text("On-device → API fallback", style = MaterialTheme.typography.labelSmall) })
        }
        if (mode != TranscriptionMode.ON_DEVICE) {
            Text("Audio is sent directly from your device to your selected transcription provider using your API key. Sprich does not provide, proxy or receive your API key. Usage is billed directly by your provider.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = providerId == "openai-compatible", onClick = { scope.launch { prefs.setSttProviderId("openai-compatible") } }, label = { Text("OpenAI-compatible", style = MaterialTheme.typography.labelSmall) })
                // Meta Muse blocked — show as disabled info, not selectable
                AssistChip(onClick = {}, enabled = false, label = { Text("Meta Muse — Not available yet", style = MaterialTheme.typography.labelSmall) })
                FilterChip(selected = providerId == "custom", onClick = { scope.launch { prefs.setSttProviderId("custom") } }, label = { Text("Custom", style = MaterialTheme.typography.labelSmall) })
            }
            // API key: Saved / Replace, never reload plaintext. Saved only if decryptable.
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("API key", style = MaterialTheme.typography.bodyMedium)
                if (hasKey) {
                    AssistChip(onClick = { showKeyEntry = true; keyInput = ""; saveError = null }, label = { Text("Saved — Replace") })
                    TextButton(onClick = { scope.launch { secretStore.removeSecret("stt_default"); hasKey = false; testResult = "Key removed" } }) { Text("Remove") }
                } else {
                    AssistChip(onClick = { showKeyEntry = true; saveError = null }, label = { Text("Add") })
                    Text("Not set", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (saveError == null) {
                        // Check if key needs re-entry due to keystore invalidation — show hint if file existed but decrypt failed recently
                        // heuristic: if hasKey false after migration, show needs entry
                    }
                }
            }
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
                                    val res = secretStore.saveSecret("stt_default", trimmed)
                                    when (res) {
                                        is SecretStoreResult.Success -> {
                                            prefs.setSttCredentialRef("stt_default")
                                            keyInput = ""
                                            showKeyEntry = false
                                            hasKey = true
                                            testResult = "Saved"
                                            saveError = null
                                        }
                                        is SecretStoreResult.Failure -> {
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
            // Removed unverified presets (xAI/Grok) — only generic OpenAI-compatible documented. Custom endpoint is expected.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    scope.launch {
                        testResult = "Testing…"
                        try {
                            if (!isValidProductionHttpsUrl(editBaseUrl)) { testResult = "Invalid URL — must be https://"; return@launch }
                            val cred = secretStore.loadSecret("stt_default")
                            if (cred.isNullOrBlank()) { testResult = "Missing API key — add key first"; return@launch }
                            // Use production provider factory + shared client + real fixture
                            val t0 = System.currentTimeMillis()
                            // Load JFK fixture for valid speech
                            val pcm = try {
                                ctx.assets.open("jfk.wav").use { Pcm16Wav.read(it).samples }
                            } catch (_: Exception) {
                                // fallback small silence if asset missing (should not happen)
                                ShortArray(16000) { (kotlin.math.sin(it * 0.01) * 8000).toInt().toShort() }
                            }
                            val trimmedPcm = if (pcm.size > 16000*8) pcm.copyOfRange(0, 16000*8) else pcm
                            val provider = com.sprich.app.speech.remote.OpenAiCompatibleSttProvider(editBaseUrl, editModel, sharedClient)
                            val req = com.sprich.app.speech.remote.RemoteSttRequest(trimmedPcm, 16000, com.sprich.app.speech.LanguagePolicy.Automatic, utteranceId = System.nanoTime(), credential = cred)
                            val res = provider.transcribe(req)
                            val dt = System.currentTimeMillis() - t0
                            if (res.text.isBlank()) { testResult = "Connected but returned blank — check model/audio"; return@launch }
                            val preview = res.text.take(40).replace("\n"," ")
                            testResult = "Connected · ${dt} ms · $preview"
                        } catch (e: Exception) {
                            val failure = com.sprich.app.speech.remote.ApiFailure.fromException(e)
                            // Try to map typed failure if RemoteSttException
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
    val baseUrl by prefs.refinementBaseUrl.collectAsState(initial = "")
    val model by prefs.refinementModel.collectAsState(initial = "")
    val secretStore = remember { ApiSecretStore(ctx) }
    val sharedClient = remember { okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build() }
    var hasKey by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var showKeyEntry by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { LegacyApiCredentialMigrator.migrateIfNeeded(prefs, secretStore) } catch (_: Exception) {}
        hasKey = try { secretStore.hasSecret("refine_default") } catch (_: Exception) { false }
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
            // Removed unverified Gemini/GPT presets — user enters verified OpenAI-compatible endpoint/model
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
                            val preview = res.text.take(40).replace("\n"," ")
                            testResult = "OK · ${ms}ms · $preview"
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
