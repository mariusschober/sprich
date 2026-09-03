package com.sprich.app.ui.vocab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PlatformImeOptions
import androidx.compose.ui.unit.dp
import com.sprich.app.R
import com.sprich.app.storage.Preferences
import com.sprich.app.ui.theme.VoiceMark
import com.sprich.app.vocab.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun VocabScreen(onBack: () -> Unit, onLearn: () -> Unit = {}) {
    val context = LocalContext.current
    val repo = remember { VocabRepository(context, Preferences(context)) }
    val scope = rememberCoroutineScope()
    var document by remember { mutableStateOf(repo.document()) }
    var spoken by remember { mutableStateOf("") }
    var written by remember { mutableStateOf("") }
    var manual by rememberSaveable { mutableStateOf(false) }
    var details by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    val limit = document.entries.size + document.learned.size >= WordLesson.MAX_WORDS
    LaunchedEffect(Unit) {
        repo.load(); document = repo.document()
        repo.changes().collect { document = repo.document() }
    }
    val selectedWord = document.learned.firstOrNull { it.id == details }
    if (selectedWord != null) AlertDialog(onDismissRequest = { details = null }, title = { Text(selectedWord.written) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(recognitionLabel(selectedWord.profile), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.learn_recorded_forms), style = MaterialTheme.typography.titleSmall)
            selectedWord.samples.forEachIndexed { index, sample -> Text(stringResource(R.string.learn_attempt_detail, index + 1, sample)) }
            Text(stringResource(R.string.learn_selected_forms), style = MaterialTheme.typography.titleSmall)
            Text(if (selectedWord.forms.isEmpty()) stringResource(R.string.learn_no_replacements) else selectedWord.forms.joinToString(" · "))
        }
    }, confirmButton = { TextButton(onClick = { details = null }) { Text(stringResource(R.string.learn_done)) } }, dismissButton = {
        TextButton(enabled = !saving, onClick = {
            saving = true
            scope.launch {
                try { repo.removeLearned(selectedWord.id); document = repo.document(); details = null; error = null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = R.string.vocab_save_failed; details = null }
                finally { saving = false }
            }
        }) { Text(stringResource(R.string.remove)) }
    })
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
        TopAppBar(title = { Text(stringResource(R.string.personal_vocabulary)) }, windowInsets = WindowInsets(0), navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                LearningCard {
                    VoiceMark(Modifier.size(64.dp).align(Alignment.CenterHorizontally))
                    Text(stringResource(R.string.vocab_learning_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.vocab_learning_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onLearn, enabled = !limit && !saving, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.learn_title)) }
                }
            }
            if (limit) item { Text(stringResource(R.string.vocab_limit)) }
            if (document.learned.isNotEmpty()) item { Text(stringResource(R.string.learned_words), style = MaterialTheme.typography.titleSmall) }
            items(document.learned, key = { it.id }) { word ->
                Surface(onClick = { details = word.id }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(word.written, style = MaterialTheme.typography.titleMedium)
                        Text(recognitionLabel(word.profile), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Text(if (word.forms.isEmpty()) stringResource(R.string.learn_hint_only) else word.forms.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                TextButton(onClick = { manual = !manual }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (manual) R.string.vocab_hide_manual else R.string.vocab_add_manual))
                }
            }
            if (manual) {
                item { Text(stringResource(R.string.vocab_description), style = MaterialTheme.typography.bodyMedium) }
                item { OutlinedTextField(value = spoken, onValueChange = { if (it.length <= 128) spoken = it }, label = { Text(stringResource(R.string.you_say)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(platformImeOptions = PlatformImeOptions(privateImeOptions = TYPED_SPELLING_IME_OPTION))) }
                item { OutlinedTextField(value = written, onValueChange = { if (it.length <= 256) written = it }, label = { Text(stringResource(R.string.sprich_writes)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(platformImeOptions = PlatformImeOptions(privateImeOptions = TYPED_SPELLING_IME_OPTION))) }
                item {
                    Button(enabled = !saving && spoken.isNotBlank() && written.isNotBlank() && !limit, modifier = Modifier.fillMaxWidth(), onClick = {
                        saving = true
                        scope.launch {
                            try { repo.add(spoken, written); document = repo.document(); spoken = ""; written = ""; error = null }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: VocabularyConflictException) { error = R.string.learn_conflict }
                            catch (_: Exception) { error = R.string.vocab_save_failed }
                            finally { saving = false }
                        }
                    }) { Text(stringResource(R.string.add_vocabulary)) }
                }
            }
            if (document.entries.isNotEmpty()) item { Text(stringResource(R.string.vocab_manual_rules), style = MaterialTheme.typography.titleSmall) }
            items(document.entries, key = { it.spoken }) { entry ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(entry.written, style = MaterialTheme.typography.titleSmall); Text(entry.spoken, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(enabled = !saving, onClick = {
                        saving = true
                        scope.launch {
                            try { repo.remove(entry.spoken); document = repo.document(); error = null }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { error = R.string.vocab_save_failed }
                            finally { saving = false }
                        }
                    }) { Text(stringResource(R.string.remove)) }
                }
            }
            error?.let { message -> item { Text(stringResource(message), color = MaterialTheme.colorScheme.error) } }
            item { Text(stringResource(R.string.vocab_learning_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
