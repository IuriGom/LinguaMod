package com.linguamod.app.ui.unit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnitDetailScreen(
    unitNumber: Int,
    onOpenLesson: (Int) -> Unit,
    onOpenCheckpoint: () -> Unit,
    onBack: () -> Unit,
    vm: UnitDetailViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(state.unit?.let { "Unit $unitNumber — ${it.title}" } ?: "Unit $unitNumber") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        LazyColumn(Modifier.fillMaxSize().padding(16.dp).testTag("lesson_list")) {
            items(state.rows) { row ->
                Card(
                    onClick = {
                        if (!row.unlocked) return@Card
                        if (row.isCheckpoint) onOpenCheckpoint() else onOpenLesson(row.index)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .testTag("lesson_row_${row.index}"),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when {
                            row.completed -> Icon(Icons.Filled.Check, "completed")
                            !row.unlocked -> Icon(Icons.Filled.Lock, "locked")
                            else -> Icon(Icons.Filled.PlayArrow, "open")
                        }
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(
                                if (row.isCheckpoint) "Checkpoint — 80% to pass" else "Lesson ${row.index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(row.title, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
