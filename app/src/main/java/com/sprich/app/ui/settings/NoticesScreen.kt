package com.sprich.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sprich.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable private data class NoticeDocument(val title: String, val path: String)
@Serializable private data class NoticeEntry(val id: String, val name: String, val version: String, val license: String,
    val source: String, val description: String, val attribution: String, val documents: List<NoticeDocument>)
private data class NoticeParagraph(val text: String, val heading: Boolean = false)

/** Reflow hard-wrapped source lines for the phone; the stored license document remains byte-for-byte intact. */
private fun readableParagraph(raw: String): String {
    val listItem = Regex("^(?:\\d+\\.|[a-z]\\.|\\([a-z0-9]+\\)|[-*•])\\s+.*")
    return buildString {
        raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            if (isNotEmpty()) append(if (listItem.matches(line)) '\n' else ' ')
            append(line)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NoticesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var entries by remember { mutableStateOf<List<NoticeEntry>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var paragraphs by remember { mutableStateOf<List<NoticeParagraph>?>(null) }
    LaunchedEffect(Unit) {
        try { entries = withContext(Dispatchers.IO) { Json.decodeFromString(context.assets.open("notices/index.json").bufferedReader().use { it.readText() }) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    val entry = entries.firstOrNull { it.id == selected }
    val matchingEntries = entries.filter { query.isBlank() || (it.name + it.version + it.license + it.attribution).contains(query, ignoreCase = true) }
    LaunchedEffect(entry) {
        paragraphs = null
        failed = false
        if (entry != null) try {
            paragraphs = withContext(Dispatchers.IO) {
                entry.documents.flatMap { doc -> listOf(NoticeParagraph(doc.title, heading = true)) + context.assets.open("notices/" + doc.path).bufferedReader().use { it.readText() }.split(Regex("\\n[ \\t]*\\n")).map { NoticeParagraph(readableParagraph(it)) } }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    fun back() { if (selected != null) { selected = null; failed = false } else onBack() }
    BackHandler(selected != null) { back() }
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
        TopAppBar(title = { Text(stringResource(R.string.licenses)) }, windowInsets = WindowInsets(0), navigationIcon = {
            IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        })
    }) { padding ->
        if (entry == null) LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.licenses_intro), style = MaterialTheme.typography.bodyLarge) }
            item { OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.licenses_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            if (failed) item { Text(stringResource(R.string.licenses_error), color = MaterialTheme.colorScheme.error) }
            if (entries.isEmpty() && !failed) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (entries.isNotEmpty() && matchingEntries.isEmpty()) item { Text(stringResource(R.string.licenses_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(matchingEntries, key = { it.id }) { notice ->
                Card(onClick = { selected = notice.id }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(notice.name, style = MaterialTheme.typography.titleMedium)
                        Text(notice.license, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item { Text(entry.name, style = MaterialTheme.typography.headlineMedium) }
                item { Text(listOf(entry.version, entry.license).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { Text(entry.description, style = MaterialTheme.typography.bodyLarge) }
                if (entry.attribution.isNotBlank()) item { Text(entry.attribution, style = MaterialTheme.typography.bodyMedium) }
                if (entry.source.isNotBlank()) item { TextButton(onClick = { uri.openUri(entry.source) }) { Text(stringResource(R.string.licenses_source)) } }
                item { HorizontalDivider() }
                if (failed) item { Text(stringResource(R.string.licenses_error), color = MaterialTheme.colorScheme.error) }
                else if (paragraphs == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                items(paragraphs.orEmpty()) { paragraph -> Text(paragraph.text, style = if (paragraph.heading) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}
