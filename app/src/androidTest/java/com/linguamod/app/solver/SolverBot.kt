package com.linguamod.app.solver

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.test.core.app.ApplicationProvider
import com.linguamod.app.fakes.FakeSpeechRecognizerGateway
import com.linguamod.app.fakes.FakeTtsGateway
import com.linguamod.app.plugin.ExerciseDto
import com.linguamod.app.plugin.ExerciseTypes
import com.linguamod.app.plugin.LinguaPluginDto
import com.linguamod.app.plugin.UnitDto
import com.linguamod.app.ui.lesson.LessonViewModel
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Lets the bot reach the scripted fake gateways without constructor plumbing. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface FakeAudioEntryPoint {
    fun fakeTts(): FakeTtsGateway
    fun fakeRecognizer(): FakeSpeechRecognizerGateway
}

/**
 * Solver Bot (Orchestrator §C.2): completes any lesson/checkpoint by deriving
 * correct answers from the plugin JSON, driving the REAL UI. Lives only in
 * androidTest code — never in any APK that ships.
 *
 * Stage 3: also solves listening (choice → the correct option; type → the
 * speakIt text), speaking (fake recognizer echoes the target phrase → pass;
 * chaos scripts garbage → fail), and sentence_scramble (taps bank tokens in
 * the correct order, reading token text from the UI so duplicates and the
 * shuffle are handled). When the fake recognizer is unavailable the engine
 * substitutes a listening-type variant of the speaking exercise, which the
 * bot then solves by typing the target phrase.
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
    private val recognizer: FakeSpeechRecognizerGateway? = runCatching {
        EntryPointAccessors.fromApplication(
            ApplicationProvider.getApplicationContext<Context>(),
            FakeAudioEntryPoint::class.java,
        ).fakeRecognizer()
    }.getOrNull()

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
        val presentable = presentable(lesson.exercises)
        clickRowAndAwaitFirstExercise(lessonIndex, presentable.firstOrNull()?.id)
        runExerciseLoop(presentable)
        rule.waitUntilExactlyOneExists(hasTestTag("lesson_complete"), LONG_TIMEOUT)
        tapUntil("finish_button", disappears = hasTestTag("lesson_complete"))
    }

    /** Returns true if the checkpoint passed.
     *  @param clickFinish set false to stay on the finish screen (e.g. snackbar assertions) */
    fun runCheckpoint(unitNumber: Int, clickFinish: Boolean = true): Boolean {
        kotlinx.coroutines.runBlocking { waitForUnlocked(unitNumber, 4) }
        val presentable = presentable(unit(unitNumber).checkpoint!!.exercises)
        clickRowAndAwaitFirstExercise(4, presentable.firstOrNull()?.id)
        runExerciseLoop(presentable)
        // runExerciseLoop already waited for a finish tag; inspect which one is shown
        val passed = rule.onAllNodes(hasTestTag("checkpoint_passed"))
            .fetchSemanticsNodes().isNotEmpty()
        if (!passed) {
            rule.waitUntilExactlyOneExists(hasTestTag("checkpoint_failed"), LONG_TIMEOUT)
        }
        if (clickFinish) tapUntil(
            "finish_button",
            disappears = hasTestTag("checkpoint_passed") or hasTestTag("checkpoint_failed"),
        )
        return passed
    }

    /**
     * Taps a lesson/checkpoint row and waits for the first exercise to appear.
     * waitForUnlocked syncs with the DB, but the row's unlocked icon lags one
     * recomposition behind; a tap on a stale locked row is swallowed by the UI.
     * Retry the tap until the lesson screen shows (bounded by LONG_TIMEOUT).
     */
    private fun clickRowAndAwaitFirstExercise(lessonIndex: Int, firstExerciseId: String?) {
        rule.onNodeWithTag("lesson_row_$lessonIndex").performClick()
        if (firstExerciseId == null) return // no presentable exercises: auto-finishes
        val deadline = System.currentTimeMillis() + LONG_TIMEOUT
        while (true) {
            try {
                rule.waitUntilExactlyOneExists(hasTestTag("exercise_$firstExerciseId"), SHORT_TIMEOUT)
                return
            } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
                if (System.currentTimeMillis() >= deadline) throw e
                // Tap again only if the row is still on screen; if it is gone the
                // navigation already happened and the exercise is just slow to render.
                if (rule.onAllNodes(hasTestTag("lesson_row_$lessonIndex"))
                        .fetchSemanticsNodes().isEmpty()
                ) continue
                rule.onNodeWithTag("lesson_row_$lessonIndex").performClick()
            }
        }
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
        val feedback = hasTestTag(if (wrong) "feedback_wrong" else "feedback_correct")
        when (e.type) {
            ExerciseTypes.MULTIPLE_CHOICE -> {
                tapOption(if (wrong) (e.correctIndex!! + 1) % 4 else e.correctIndex!!)
                submitAndContinue(feedback)
            }
            ExerciseTypes.LISTENING -> when (e.mode) {
                "type" -> {
                    typeAnswer(if (wrong) GARBAGE else e.speakIt!!)
                    submitAndContinue(feedback)
                }
                else -> {
                    tapOption(if (wrong) (e.correctIndex!! + 1) % 4 else e.correctIndex!!)
                    submitAndContinue(feedback)
                }
            }
            ExerciseTypes.FILL_BLANK, ExerciseTypes.TRANSLATION_IT_EN,
            ExerciseTypes.TRANSLATION_EN_IT,
            -> {
                val answer = if (wrong) GARBAGE else when (e.type) {
                    ExerciseTypes.FILL_BLANK -> e.answers!!.first()
                    ExerciseTypes.TRANSLATION_IT_EN -> e.acceptedEn!!.first()
                    else -> e.acceptedIt!!.first()
                }
                typeAnswer(answer)
                submitAndContinue(feedback)
            }
            ExerciseTypes.SPEAKING -> answerSpeaking(e, wrong, feedback)
            ExerciseTypes.SENTENCE_SCRAMBLE -> {
                answerScramble(e, wrong)
                submitAndContinue(feedback)
            }
            else -> error("Solver bot: unsupported type ${e.type}")
        }
    }

    /**
     * Speaking: the fake recognizer echoes the target phrase (pass); chaos
     * scripts a garbage transcript (fail, "completely different"). If the fake
     * is unavailable or mic-denied, the engine substituted a listening-type
     * variant of the same phrase — solved by typing the target.
     */
    private fun answerSpeaking(e: ExerciseDto, wrong: Boolean, feedback: SemanticsMatcher) {
        val rec = recognizer
        if (rec != null && (!rec.isRecognitionAvailable() || !rec.isMicPermissionGranted())) {
            typeAnswer(if (wrong) GARBAGE else e.targetIt!!)
            submitAndContinue(feedback)
            return
        }
        rec?.script = if (wrong) GARBAGE else null // null script → echo the target phrase
        try {
            tapUntil("speak_mic", appears = feedback)
        } finally {
            rec?.script = null
        }
        tapUntil("continue_button", disappears = feedback)
    }

    /** Scramble: taps bank tokens in the wanted order, reading the token text
     *  from the UI (the shuffle is opaque to the bot; duplicates are identical). */
    private fun answerScramble(e: ExerciseDto, wrong: Boolean) {
        val correctOrder = e.correctSentence!!.trim().split(Regex("\\s+"))
        var order = correctOrder
        if (wrong) {
            val wrongOrder = wrongPermutation(correctOrder)
            if (wrongOrder == null) {
                order = correctOrder // all tokens identical: cannot fail — answer correctly
            } else {
                order = wrongOrder
            }
        }
        for (token in order) tapBankToken(token)
    }

    /** A permutation guaranteed to mismatch the target, or null if none exists. */
    private fun wrongPermutation(order: List<String>): List<String>? {
        val target = LessonViewModel.normalizeWhitespace(order.joinToString(" "))
        val candidates = listOf(order.reversed()) + order.indices.map { i ->
            order.toMutableList().also { it.add(it.removeAt(i)) } // move token i to the end
        }
        return candidates.firstOrNull {
            LessonViewModel.normalizeWhitespace(it.joinToString(" ")) != target
        }
    }

    /** Taps the first bank token whose text equals [token]; retries while
     *  recomposition lags behind the previous tap. */
    private fun tapBankToken(token: String) {
        val deadline = System.currentTimeMillis() + LONG_TIMEOUT
        while (true) {
            var i = 0
            while (true) {
                val nodes = rule.onAllNodes(hasTestTag("scramble_bank_$i")).fetchSemanticsNodes()
                if (nodes.isEmpty()) break
                val text = nodes.first().config.getOrNull(SemanticsProperties.Text)
                    ?.joinToString("") { it.text }
                if (text == token) {
                    rule.onNodeWithTag("scramble_bank_$i").performClick()
                    rule.waitForIdle()
                    return
                }
                i++
            }
            if (System.currentTimeMillis() >= deadline) {
                error("Solver bot: scramble bank token '$token' not found")
            }
            Thread.sleep(100)
        }
    }

    private fun tapOption(i: Int) {
        // Under emulator load a tap can be swallowed mid-recomposition; retry
        // until the selection lands (bounded).
        val deadline = System.currentTimeMillis() + LONG_TIMEOUT
        while (true) {
            rule.onNodeWithTag("option_$i").performClick()
            try {
                rule.waitUntil(SHORT_TIMEOUT) {
                    rule.onNodeWithTag("submit_button")
                        .fetchSemanticsNode().config
                        .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == null
                }
                return
            } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
                if (System.currentTimeMillis() >= deadline) throw e
            }
        }
    }

    private fun typeAnswer(answer: String) {
        rule.onNodeWithTag("answer_field").performTextInput(answer)
    }

    /** Common tail: tap Check, wait for the expected feedback, tap Continue. */
    private fun submitAndContinue(feedback: SemanticsMatcher) {
        tapUntil("submit_button", appears = feedback)
        tapUntil("continue_button", disappears = feedback)
    }

    /**
     * Taps [tag] and waits for the expected effect; retries the tap if the effect
     * does not arrive within SHORT_TIMEOUT. Gives up (rethrow) after LONG_TIMEOUT
     * or when the button itself is gone (effect already consumed elsewhere).
     */
    private fun tapUntil(
        tag: String,
        appears: SemanticsMatcher? = null,
        disappears: SemanticsMatcher? = null,
    ) {
        val deadline = System.currentTimeMillis() + LONG_TIMEOUT
        while (true) {
            rule.onNodeWithTag(tag).performClick()
            try {
                if (appears != null) rule.waitUntilExactlyOneExists(appears, SHORT_TIMEOUT)
                if (disappears != null) rule.waitUntilDoesNotExist(disappears, SHORT_TIMEOUT)
                return
            } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
                if (System.currentTimeMillis() >= deadline) throw e
                if (rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()) throw e
            }
        }
    }

    companion object {
        const val LONG_TIMEOUT = 30_000L
        const val SHORT_TIMEOUT = 8_000L

        /** Wrong-answer text: token count never matches any accepted variant. */
        const val GARBAGE = "xxxxx yyyyy zzzzz"
    }
}
