package com.sprich.app.ui.vocab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sprich.app.R
import com.sprich.app.storage.Preferences
import com.sprich.app.vocab.VocabRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun VocabScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { VocabRepository(context, Preferences(context)) }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(repo.entries()) }
    var spoken by rememberSaveable { mutableStateOf("") }
    var written by rememberSaveable { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { repo.load(); entries = repo.entries() }
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
        TopAppBar(title = { Text(stringResource(R.string.personal_vocabulary)) }, windowInsets = WindowInsets(0), navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text(stringResource(R.string.vocab_description), style = MaterialTheme.typography.bodyMedium) }
            item { OutlinedTextField(value = spoken, onValueChange = { if (it.length <= 128) spoken = it }, label = { Text(stringResource(R.string.you_say)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(value = written, onValueChange = { if (it.length <= 256) written = it }, label = { Text(stringResource(R.string.sprich_writes)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item {
                Button(enabled = !saving && spoken.isNotBlank() && written.isNotBlank() && entries.size < 200, modifier = Modifier.fillMaxWidth(), onClick = {
                    saving = true
                    scope.launch {
                        try { repo.add(spoken, written); entries = repo.entries(); spoken = ""; written = ""; failed = false }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failed = true }
                        finally { saving = false }
                    }
                }) { Text(stringResource(R.string.add_vocabulary)) }
                if (failed) Text(stringResource(R.string.vocab_save_failed), color = MaterialTheme.colorScheme.error)
                if (entries.size >= 200) Text(stringResource(R.string.vocab_limit))
            }
            item { HorizontalDivider() }
            if (entries.isEmpty()) item { Text(stringResource(R.string.vocab_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(entries, key = { it.spoken }) { entry ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(entry.written, style = MaterialTheme.typography.titleSmall); Text(entry.spoken, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick = { scope.launch {
                        try { repo.remove(entry.spoken); entries = repo.entries(); failed = false }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failed = true }
                    } }) { Text(stringResource(R.string.remove)) }
                }
            }
        }
    }
}
