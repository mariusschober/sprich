package com.sprich.app.ui.vocab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sprich.app.storage.Preferences
import com.sprich.app.vocab.VocabRepository
import kotlinx.coroutines.launch

@Composable
fun VocabScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Preferences(ctx) }
    val repo = remember { VocabRepository(ctx, prefs) }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(repo.entries()) }
    var spoken by remember { mutableStateOf("") }
    var written by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { repo.load(); entries = repo.entries() }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            CenterAlignedTopAppBar(
                title = { Text("Personal vocabulary") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "When you say a word, its written form is inserted. Perfect for names, jargon and brands.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = spoken,
                onValueChange = { spoken = it },
                label = { Text("You say") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = written,
                onValueChange = { written = it },
                label = { Text("Sprich writes") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    scope.launch {
                        if (spoken.isNotBlank() && written.isNotBlank()) {
                            repo.add(spoken.trim(), written.trim())
                            entries = repo.entries()
                            spoken = ""; written = ""
                        }
                    }
                },
                enabled = spoken.isNotBlank() && written.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Add to vocabulary") }

            HorizontalDivider()

            if (entries.isEmpty()) {
                Text(
                    "No entries yet. Try: you say \"marius\" → Sprich writes \"Marius K.\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                    items(entries, key = { it.spoken }) { e ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.written, style = MaterialTheme.typography.bodyMedium)
                                Text("“${e.spoken}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { scope.launch { repo.remove(e.spoken); entries = repo.entries() } }) {
                                Text("Remove", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                TextButton(onClick = { scope.launch { repo.clear(); entries = repo.entries() } }) {
                    Text("Clear all", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
