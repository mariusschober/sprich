package com.sprich.app.ui.settings

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
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: ()->Unit, onBenchmark: ()->Unit){
    val ctx = LocalContext.current
    val prefs = remember { Preferences(ctx) }
    val mm = remember { ModelManager(ctx) }
    val dm = remember { DownloadManager(ctx, mm) }
    val scope = rememberCoroutineScope()

    val instant by prefs.instantMode.collectAsState(initial = false)
    val lang by prefs.language.collectAsState(initial = Language.AUTO)
    val engine by prefs.engineType.collectAsState(initial = EngineType.FAST)
    val haptics by prefs.haptics.collectAsState(initial = true)
    val commands by prefs.commands.collectAsState(initial = true)
    val canaryStatus by mm.canaryStatus.collectAsState()
    val nemoStatus by mm.nemotronStatus.collectAsState()

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
                nemoStatus = nemoStatus,
                onSelect = { scope.launch{ prefs.setEngine(it)} },
                onDownloadCanary = { scope.launch { try{ dm.downloadCanary() } catch (_:Exception){} } },
                onDeleteCanary = { scope.launch { mm.deleteCanary() } },
                onDownloadNemotron = { scope.launch { try{ dm.downloadNemotron() } catch (_:Exception){} } },
                onDeleteNemotron = { scope.launch { mm.deleteNemotron() } },
                onCancel = { dm.cancel() }
            )

            HorizontalDivider()
            Text("Personalization", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            SettingsRow("Personal vocabulary", "Manage names & terms"){}
            SettingsToggle("Learn my corrections", "Off by default", false){}

            HorizontalDivider()
            Text("Behavior", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            SettingsToggle("Haptic feedback", "On", haptics){ scope.launch{ prefs.setHaptics(it)} }
            SettingsToggle("Spoken editing", "On", commands){ scope.launch{ prefs.setCommands(it)} }
            SettingsRow("Stop after silence", "Automatic"){ }

            HorizontalDivider()
            Text("Privacy", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            SettingsRow("Audio storage", "Never"){}
            SettingsRow("Network use", "Models only"){}
            SettingsRow("Clear local data", "", actionLabel = "Clear", onClick = { scope.launch{ prefs.clearAll(); mm.deleteCanary(); mm.deleteNemotron() } })

            HorizontalDivider()
            Text("Advanced", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            SettingsRow("Current engine", when(engine){ EngineType.FAST->"Whisper Base Q5_1"; EngineType.ACCURATE->"Canary 180M Flash"; EngineType.STREAMING->"Nemotron 0.6B" }){}
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
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween){
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
    nemoStatus: ModelStatus,
    onSelect:(EngineType)->Unit,
    onDownloadCanary:()->Unit,
    onDeleteCanary:()->Unit,
    onDownloadNemotron:()->Unit,
    onDeleteNemotron:()->Unit,
    onCancel:()->Unit,
){
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)){
        Text("Speech model", style = MaterialTheme.typography.bodyMedium)
        ModelCard("Fast", "57 MB · Included", "Fastest compatibility and lowest resource use.", selected = engine==EngineType.FAST, status = ModelStatus.Ready, onClick = { onSelect(EngineType.FAST) })
        ModelCardAdvanced("Accurate", "147 MB · Download", "Higher recognition quality.", selected = engine==EngineType.ACCURATE, status = canaryStatus, onClick = { onSelect(EngineType.ACCURATE) }, onDownload = onDownloadCanary, onDelete = onDeleteCanary, onCancel = onCancel, totalMb = 147)
        ModelCardAdvanced("Instant Streaming", "630 MB · Download", "Lowest potential live latency on capable phones.", selected = engine==EngineType.STREAMING, status = nemoStatus, onClick = { onSelect(EngineType.STREAMING) }, onDownload = onDownloadNemotron, onDelete = onDeleteNemotron, onCancel = onCancel, totalMb = 630)
        TextButton(onClick = {}){ Text("Technical details", style = MaterialTheme.typography.labelSmall) }
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
