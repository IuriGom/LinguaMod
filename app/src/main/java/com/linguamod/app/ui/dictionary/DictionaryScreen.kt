package com.linguamod.app.ui.dictionary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.linguamod.app.data.db.DictionaryEntryEntity
import kotlinx.serialization.json.Json
import com.linguamod.app.plugin.ExampleDto

@Composable
fun DictionaryScreen(vm: DictionaryViewModel = hiltViewModel()) {
    val entries by vm.entries.collectAsState()
    var q by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Dictionary", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = q,
            onValueChange = { q = it; vm.setQuery(it) },
            modifier = Modifier.fillMaxWidth().testTag("dictionary_search"),
            label = { Text("Search") },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        if (entries.isEmpty()) {
            Text(
                "Words appear here as you learn them. Start Unit 1!",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("dictionary_empty"),
            )
        } else {
            LazyColumn(Modifier.testTag("dictionary_list")) {
                items(entries, key = { it.id }) { entry -> DictionaryRow(entry) }
            }
        }
    }
}

private val json = Json { ignoreUnknownKeys = true }

@Composable
private fun DictionaryRow(e: DictionaryEntryEntity) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row {
                val display = if (e.article != null) "${e.article} ${e.word}" else e.word
                Text(display, style = MaterialTheme.typography.titleMedium)
                if (e.partOfSpeech == "noun" && e.gender != null) {
                    Spacer(Modifier.padding(start = 8.dp))
                    AssistChip(onClick = {}, label = { Text(e.gender) })
                }
            }
            Text(e.translation, style = MaterialTheme.typography.bodyMedium)
            Text(e.partOfSpeech, style = MaterialTheme.typography.labelSmall)
            val examples = runCatching {
                json.decodeFromString<List<ExampleDto>>(e.examplesJson)
            }.getOrDefault(emptyList())
            examples.forEach { ex ->
                Spacer(Modifier.height(4.dp))
                Text("«${ex.it}» — ${ex.en}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
