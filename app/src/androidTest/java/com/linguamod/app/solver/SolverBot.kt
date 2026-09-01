package com.linguamod.app.solver

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.waitUntilExactlyOneExists
import com.linguamod.app.plugin.ExerciseDto
import com.linguamod.app.plugin.ExerciseTypes
import com.linguamod.app.plugin.LinguaPluginDto
import com.linguamod.app.plugin.UnitDto
import com.linguamod.app.ui.lesson.LessonViewModel

/**
 * Solver Bot (Orchestrator §C.2): completes any lesson/checkpoint by deriving
 * correct answers from the plugin JSON, driving the REAL UI. Lives only in
 * androidTest code — never in any APK that ships.
 *
 * @param chaos when true, answers wrong on purpose the first time each exercise
 *              is seen (tests failure paths)
 * @param chaosStaysWrong when true, re-queued exercises are answered wrong again
 *              (forces a checkpoint failure)
 */
@OptIn(ExperimentalTestApi::class)
class SolverBot(
    private val rule: ComposeTestRule,
    private val plugin: LinguaPluginDto,
    private val chaos: Boolean = false,
    private val chaosStaysWrong: Boolean = false,
    private val db: com.linguamod.app.data.db.AppDatabase? = null,
) {
    private fun unit(n: Int): UnitDto = plugin.units.first { it.number == n }

    private fun presentable(exercises: List<ExerciseDto>): List<ExerciseDto> =
        exercises.filter { it.type in LessonViewModel.SUPPORTED_TYPES }

    /** Sync with the engine's async progress writes before tapping a row. */
    private suspend fun waitForUnlocked(unitNumber: Int, lessonIndex: Int) {
        val database = db ?: return
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val ok = if (lessonIndex == 0) {
                unitNumber == 1 ||
                    database.progressDao().getLessonProgress(unitNumber - 1, 4)?.completed == true
            } else if (lessonIndex == 4) {
                (0..3).all { database.progressDao().getLessonProgress(unitNumber, it)?.completed == true }
            } else {
                database.progressDao().getLessonProgress(unitNumber, lessonIndex - 1)?.completed == true
            }
            if (ok) return
            kotlinx.coroutines.delay(100)
        }
        error("SolverBot: timed out waiting for unit $unitNumber lesson $lessonIndex to unlock")
    }

    fun completeLesson(unitNumber: Int, lessonIndex: Int) {
        kotlinx.coroutines.runBlocking { waitForUnlocked(unitNumber, lessonIndex) }
        val lesson = unit(unitNumber).lessons!![lessonIndex]
        rule.onNodeWithTag("lesson_row_$lessonIndex").performClick()
        runExerciseLoop(presentable(lesson.exercises))
        rule.waitUntilExactlyOneExists(hasTestTag("lesson_complete"), LONG_TIMEOUT)
        rule.onNodeWithTag("finish_button").performClick()
    }

    /** Returns true if the checkpoint passed. */
    fun runCheckpoint(unitNumber: Int): Boolean {
        kotlinx.coroutines.runBlocking { waitForUnlocked(unitNumber, 4) }
        rule.onNodeWithTag("lesson_row_4").performClick()
        runExerciseLoop(presentable(unit(unitNumber).checkpoint!!.exercises))
        // runExerciseLoop already waited for a finish tag; inspect which one is shown
        val passed = rule.onAllNodes(hasTestTag("checkpoint_passed"))
            .fetchSemanticsNodes().isNotEmpty()
        if (!passed) {
            rule.waitUntilExactlyOneExists(hasTestTag("checkpoint_failed"), LONG_TIMEOUT)
        }
        rule.onNodeWithTag("finish_button").performClick()
        return passed
    }

    /** Mirrors the engine: exercises in plugin order; wrong ones re-queue once at the end. */
    private fun runExerciseLoop(exercises: List<ExerciseDto>) {
        val sequence = exercises.toMutableList()
        val seenOnce = mutableSetOf<String>()
        val requeued = ArrayDeque<ExerciseDto>()
        var idx = 0
        while (idx < sequence.size) {
            val e = sequence[idx]
            val firstTime = seenOnce.add(e.id!!)
            val answerWrong = chaos && (firstTime || chaosStaysWrong)
            answerExercise(e, answerWrong)
            if (answerWrong && firstTime) requeued.addLast(e) // engine re-queues once
            idx++
            if (idx == sequence.size && requeued.isNotEmpty()) {
                sequence += requeued.toList()
                requeued.clear()
            }
        }
        rule.waitUntilExactlyOneExists(
            hasTestTag("lesson_complete") or hasTestTag("checkpoint_passed") or hasTestTag("checkpoint_failed"),
            LONG_TIMEOUT,
        )
    }

    private fun answerExercise(e: ExerciseDto, wrong: Boolean) {
        rule.waitUntilExactlyOneExists(hasTestTag("exercise_${e.id}"), LONG_TIMEOUT)
        when (e.type) {
            ExerciseTypes.MULTIPLE_CHOICE -> {
                val idx = if (wrong) (e.correctIndex!! + 1) % 4 else e.correctIndex!!
                rule.onNodeWithTag("option_$idx").performClick()
            }
            ExerciseTypes.FILL_BLANK, ExerciseTypes.TRANSLATION_IT_EN, ExerciseTypes.TRANSLATION_EN_IT -> {
                val answer = if (wrong) "xxxxx" else when (e.type) {
                    ExerciseTypes.FILL_BLANK -> e.answers!!.first()
                    ExerciseTypes.TRANSLATION_IT_EN -> e.acceptedEn!!.first()
                    else -> e.acceptedIt!!.first()
                }
                rule.onNodeWithTag("answer_field").performTextInput(answer)
            }
            else -> error("Solver bot: unsupported type ${e.type} (Stage 1)")
        }
        rule.onNodeWithTag("submit_button").performClick()
        rule.waitUntilExactlyOneExists(
            hasTestTag(if (wrong) "feedback_wrong" else "feedback_correct"),
            LONG_TIMEOUT,
        )
        rule.onNodeWithTag("continue_button").performClick()
    }

    companion object {
        const val LONG_TIMEOUT = 30_000L
        const val SHORT_TIMEOUT = 8_000L
    }
}
