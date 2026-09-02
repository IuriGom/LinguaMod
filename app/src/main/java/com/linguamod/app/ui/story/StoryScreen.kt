package com.linguamod.app.ui.story

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.linguamod.app.data.CourseRepository

/** Story player (Stage 4 §2, spec §7). */
@Composable
fun StoryScreen(
    onDone: () -> Unit,
    vm: StoryViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()

    when {
        state.loading -> Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) { CircularProgressIndicator() }

        state.missing -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("This story is not in the loaded course.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = onDone, modifier = Modifier.testTag("finish_button")) { Text("Back") }
        }

        // Registered placeholder (nodes: []) — never crashes, explains itself.
        state.placeholder -> Column(
            Modifier.fillMaxSize().padding(24.dp).testTag("story_placeholder"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(state.title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                "This story arrives with a future content pack.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onDone, modifier = Modifier.testTag("finish_button")) { Text("Back") }
        }

        state.completed -> Column(
            Modifier.fillMaxSize().padding(24.dp).testTag("story_complete"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Story complete!", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            if (state.xpAwarded) {
                Text(
                    "+${CourseRepository.XP_PER_STORY} XP",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("story_xp"),
                )
                Text(
                    "Badge unlocked: Narratore",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("story_badge"),
                )
            } else {
                Text(
                    "You've already earned this story's reward — enjoy the rerun.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = { vm.replay() },
                modifier = Modifier.fillMaxWidth().testTag("story_replay"),
            ) { Text("Read again") }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().testTag("finish_button"),
            ) { Text("Done") }
        }

        else -> {
            val node = state.node ?: return
            Column(
                Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())
                    .testTag("story_screen"),
            ) {
                Text(state.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            node.speaker ?: "",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("story_speaker"),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            node.textIt ?: "",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.testTag("story_text"),
                        )
                        if (state.showEnglish && !node.textEn.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                node.textEn,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("story_text_en"),
                            )
                        }
                    }
                }
                if (!node.textEn.isNullOrBlank()) {
                    TextButton(
                        onClick = { vm.toggleEnglish() },
                        modifier = Modifier.testTag("story_toggle_en"),
                    ) { Text(if (state.showEnglish) "Hide English" else "Show English") }
                }
                val feedback = state.feedback
                if (feedback != null) {
                    Spacer(Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("story_feedback"),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Not quite.", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(feedback, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (node.terminal) {
                    Button(
                        onClick = { vm.finishStory() },
                        modifier = Modifier.fillMaxWidth().testTag("story_finish"),
                    ) { Text("Finish the story") }
                } else {
                    node.choices.forEachIndexed { i, choice ->
                        Button(
                            onClick = { vm.choose(choice) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                .testTag("story_choice_$i"),
                        ) { Text(choice.text ?: "") }
                    }
                }
            }
        }
    }
}
