package com.linguamod.app.ui.lesson

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.linguamod.app.data.CourseRepository
import com.linguamod.app.data.FeatureUnlocks
import com.linguamod.app.plugin.ExerciseDto
import com.linguamod.app.plugin.ExerciseTypes

@Composable
fun LessonScreen(
    isCheckpoint: Boolean,
    onDone: () -> Unit,
    vm: LessonViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()

    when {
        state.loading -> Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) { CircularProgressIndicator() }

        state.finished -> FinishedWithUnlocks(state = state, vm = vm, onDone = onDone)

        else -> Column(
            Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { if (state.total == 0) 0f else state.position.toFloat() / state.total },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                HeartsDisplay(state.hearts)
            }
            if (state.hearts == 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Out of hearts — take your time, you can keep practicing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            val e = state.exercise
            if (e != null) {
                Text(e.prompt ?: "", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.testTag("exercise_${e.id}")) {
                    ExerciseBody(e, state, vm)
                }
            }

            val fb = state.feedback
            if (fb != null) {
                Spacer(Modifier.height(16.dp))
                FeedbackCard(fb)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { vm.next() },
                    modifier = Modifier.fillMaxWidth().testTag("continue_button"),
                ) { Text("Continue") }
            }
        }
    }
}

/** Hearts, top of the lesson screen. Empty hearts never block play (Stage 2B §5). */
@Composable
private fun HeartsDisplay(hearts: Int) {
    Row(Modifier.testTag("hearts_display"), verticalAlignment = Alignment.CenterVertically) {
        repeat(CourseRepository.MAX_HEARTS) { i ->
            Icon(
                if (i < hearts) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Finish screen + one-time, non-blocking "New feature unlocked" snackbar. */
@Composable
private fun FinishedWithUnlocks(state: LessonState, vm: LessonViewModel, onDone: () -> Unit) {
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(state.newUnlocks) {
        if (state.newUnlocks.isNotEmpty()) {
            val names = state.newUnlocks.joinToString { FeatureUnlocks.displayName(it) }
            vm.markUnlocksShown() // persist first so it can never re-show
            snackbarHost.showSnackbar("New feature unlocked: $names")
        }
    }
    Box(Modifier.fillMaxSize()) {
        FinishScreen(state = state, onDone = onDone)
        SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter)) { data ->
            Snackbar(data, modifier = Modifier.testTag("unlock_snackbar"))
        }
    }
}

@Composable
private fun ExerciseBody(e: ExerciseDto, state: LessonState, vm: LessonViewModel) {
    when (e.type) {
        ExerciseTypes.MULTIPLE_CHOICE -> {
            Text(e.question ?: "", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            e.options.orEmpty().forEachIndexed { i, option ->
                val selected = state.selectedOption == i
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .selectable(selected = selected, onClick = { vm.selectOption(i) })
                        .testTag("option_$i"),
                ) {
                    Text(
                        option,
                        Modifier.padding(20.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            SubmitButton(enabled = state.selectedOption != null, onSubmit = vm::submit)
        }

        ExerciseTypes.FILL_BLANK -> {
            Text(e.sentence ?: "", style = MaterialTheme.typography.headlineSmall)
            AnswerField(state, vm)
        }

        ExerciseTypes.TRANSLATION_IT_EN -> {
            Text(e.sourceIt ?: "", style = MaterialTheme.typography.headlineSmall)
            AnswerField(state, vm)
        }

        ExerciseTypes.TRANSLATION_EN_IT -> {
            Text(e.sourceEn ?: "", style = MaterialTheme.typography.headlineSmall)
            AnswerField(state, vm)
        }
    }
}

@Composable
private fun AnswerField(state: LessonState, vm: LessonViewModel) {
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = state.typedAnswer,
        onValueChange = { vm.updateTyped(it) },
        modifier = Modifier.fillMaxWidth().testTag("answer_field"),
        label = { Text("Your answer") },
        singleLine = true,
        enabled = state.feedback == null,
    )
    SubmitButton(enabled = state.typedAnswer.isNotBlank(), onSubmit = vm::submit)
}

@Composable
private fun SubmitButton(enabled: Boolean, onSubmit: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = onSubmit,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("submit_button"),
    ) { Text("Check") }
}

@Composable
private fun FeedbackCard(fb: Feedback) {
    val correct = fb is Feedback.Correct
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (correct) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth().testTag(if (correct) "feedback_correct" else "feedback_wrong"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (correct) "Correct!" else "Not quite.",
                style = MaterialTheme.typography.titleMedium,
            )
            val explanation = when (fb) {
                is Feedback.Correct -> fb.explanation
                is Feedback.Wrong -> fb.explanation
            }
            if (explanation.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(explanation, style = MaterialTheme.typography.bodyMedium)
            }
            if (fb is Feedback.Wrong) {
                Spacer(Modifier.height(4.dp))
                Text("Correct answer: ${fb.correctAnswer}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun FinishScreen(state: LessonState, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (state.isCheckpoint) {
            if (state.passed) {
                Text(
                    "Checkpoint passed!",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.testTag("checkpoint_passed"),
                )
                Text("${state.correctCount}/${state.answeredCount} correct (${(state.score * 100).toInt()}%)")
                Text("Next unit unlocked.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    "Almost there!",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.testTag("checkpoint_failed"),
                )
                Text("${state.correctCount}/${state.answeredCount} correct — you need 80%.")
                Text(
                    "Review the lessons and try again. No penalty — you've got this.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Text(
                "Lesson complete!",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.testTag("lesson_complete"),
            )
            Text("${state.correctCount}/${state.answeredCount} correct")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "+${state.xpGained} XP",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("xp_gain"),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDone, modifier = Modifier.testTag("finish_button")) {
            Text(if (state.isCheckpoint && !state.passed) "Back to lessons" else "Done")
        }
    }
}
