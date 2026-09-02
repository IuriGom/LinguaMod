package com.linguamod.app.ui.flashcard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.linguamod.app.plugin.ExampleDto
import com.linguamod.app.ui.common.SpeakerButton
import kotlinx.serialization.json.Json

/**
 * Flashcards (Stage 3 §6). Front: Italian (article + speaker icon); tap to
 * flip → English + example + gender badge. Self-grading "Got it" /
 * "Still learning"; the latter re-queues the card within the session.
 */
@Composable
fun FlashcardScreen(
    onDone: () -> Unit,
    vm: FlashcardViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()

    when {
        state.loading -> Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) { CircularProgressIndicator() }

        state.empty -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "No cards yet — start Unit 1 to build your deck.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("flashcard_empty"),
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onDone, modifier = Modifier.testTag("flashcard_back")) {
                Text("Back")
            }
        }

        state.finished -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Session complete!",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.testTag("flashcard_done"),
            )
            Text("${state.total} cards reviewed")
            Spacer(Modifier.height(24.dp))
            Button(onClick = onDone, modifier = Modifier.testTag("finish_button")) {
                Text("Done")
            }
        }

        else -> {
            val entry = state.current ?: return
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text(
                    "Card ${state.position + 1} of ${state.total}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag("flashcard_progress"),
                )
                Spacer(Modifier.height(16.dp))
                Card(
                    onClick = { vm.flip() },
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("flashcard_card"),
                ) {
                    Column(
                        Modifier.fillMaxSize().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (!state.flipped) {
                            val display =
                                if (entry.article != null) "${entry.article} ${entry.word}" else entry.word
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    display,
                                    style = MaterialTheme.typography.headlineMedium,
                                    modifier = Modifier.testTag("flashcard_word"),
                                )
                                SpeakerButton(display, tag = "flashcard_speaker")
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Tap to flip",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                entry.translation,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.testTag("flashcard_translation"),
                            )
                            if (entry.partOfSpeech == "noun" && entry.gender != null) {
                                Spacer(Modifier.height(8.dp))
                                AssistChip(
                                    onClick = {},
                                    label = { Text(entry.gender) },
                                    modifier = Modifier.testTag("flashcard_gender"),
                                )
                            }
                            val example = runCatching {
                                json.decodeFromString<List<ExampleDto>>(entry.examplesJson)
                            }.getOrDefault(emptyList()).firstOrNull()
                            if (example?.it != null) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "«${example.it}» — ${example.en ?: ""}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.testTag("flashcard_example"),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { vm.gradeStillLearning() },
                        modifier = Modifier.weight(1f).testTag("flashcard_still_learning"),
                    ) { Text("Still learning") }
                    Button(
                        onClick = { vm.gradeGotIt() },
                        modifier = Modifier.weight(1f).testTag("flashcard_got_it"),
                    ) { Text("Got it") }
                }
            }
        }
    }
}

private val json = Json { ignoreUnknownKeys = true }
