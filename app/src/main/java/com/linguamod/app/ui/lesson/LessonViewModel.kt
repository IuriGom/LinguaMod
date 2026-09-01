package com.linguamod.app.ui.lesson

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linguamod.app.data.CourseRepository
import com.linguamod.app.plugin.AnswerMatcher
import com.linguamod.app.plugin.ExerciseDto
import com.linguamod.app.plugin.ExerciseTypes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface Feedback {
    data class Correct(val explanation: String) : Feedback
    data class Wrong(val explanation: String, val correctAnswer: String) : Feedback
}

data class LessonState(
    val loading: Boolean = true,
    val title: String = "",
    val isCheckpoint: Boolean = false,
    val exercise: ExerciseDto? = null,
    val position: Int = 0,
    val total: Int = 0,
    val selectedOption: Int? = null,
    val typedAnswer: String = "",
    val feedback: Feedback? = null,
    val finished: Boolean = false,
    val correctCount: Int = 0,
    val answeredCount: Int = 0,
    val passed: Boolean = false,
) {
    val score: Double get() = if (answeredCount == 0) 0.0 else correctCount.toDouble() / answeredCount
}

/** Lesson engine: one exercise at a time; wrong exercises re-queue once at the end.
 *  Shared by normal lessons and checkpoints. Exercise types not implemented in this
 *  stage are skipped with a log (they never block completion). */
@HiltViewModel
class LessonViewModel @Inject constructor(
    private val repo: CourseRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val unitNumber: Int = checkNotNull(savedState["unit"])
    private val lessonIndex: Int? = savedState["lesson"]
    val isCheckpoint: Boolean = lessonIndex == null

    private val _state = MutableStateFlow(LessonState(isCheckpoint = isCheckpoint))
    val state: StateFlow<LessonState> = _state

    private var queue = ArrayDeque<ExerciseDto>()
    private val requeued = mutableSetOf<String>()
    private var firstTryCorrect = 0
    private var firstTryAnswered = 0

    init {
        viewModelScope.launch {
            repo.initialize()
            repo.markLessonStarted(unitNumber, lessonIndex ?: CourseRepository.CHECKPOINT_INDEX)
            val plugin = repo.plugin.value ?: run {
                // plugin flow may not have emitted yet; wait for it
                var p = repo.plugin.value
                while (p == null) { kotlinx.coroutines.delay(50); p = repo.plugin.value }
                p
            }
            val unit = plugin.units.firstOrNull { it.number == unitNumber }
            val exercises = when {
                unit == null -> emptyList()
                isCheckpoint -> unit.checkpoint?.exercises.orEmpty()
                else -> unit.lessons?.getOrNull(lessonIndex ?: 0)?.exercises.orEmpty()
            }
            val presentable = exercises.filter {
                val supported = it.type in SUPPORTED_TYPES
                if (!supported) Log.i(TAG, "skipping unsupported exercise type ${it.type} (${it.id})")
                supported
            }
            queue = ArrayDeque(presentable)
            _state.value = LessonState(
                loading = false,
                title = unit?.let { "Unit ${it.number} — ${it.title}" } ?: "",
                isCheckpoint = isCheckpoint,
                exercise = queue.firstOrNull(),
                total = presentable.size,
            )
            if (presentable.isEmpty()) finish() // all exercises skipped: nothing to fail
        }
    }

    fun selectOption(i: Int) {
        if (_state.value.feedback != null) return
        _state.value = _state.value.copy(selectedOption = i)
    }

    fun updateTyped(s: String) {
        if (_state.value.feedback != null) return
        _state.value = _state.value.copy(typedAnswer = s)
    }

    fun submit() {
        val s = _state.value
        val e = s.exercise ?: return
        if (s.feedback != null) return
        val correct = when (e.type) {
            ExerciseTypes.MULTIPLE_CHOICE -> s.selectedOption == e.correctIndex
            ExerciseTypes.FILL_BLANK -> AnswerMatcher.matchesAny(e.answers.orEmpty(), s.typedAnswer)
            ExerciseTypes.TRANSLATION_IT_EN -> AnswerMatcher.matchesAny(e.acceptedEn.orEmpty(), s.typedAnswer)
            ExerciseTypes.TRANSLATION_EN_IT -> AnswerMatcher.matchesAny(e.acceptedIt.orEmpty(), s.typedAnswer)
            else -> false
        }
        val isRetry = e.id in requeued
        if (!isRetry) {
            firstTryAnswered++
            if (correct) firstTryCorrect++
        }
        viewModelScope.launch { repo.recordExerciseResult(e.id!!, correct) }
        if (correct) viewModelScope.launch { repo.addXp(XP_PER_CORRECT) }
        val fb = if (correct) {
            Feedback.Correct(e.explanation ?: "")
        } else {
            Feedback.Wrong(e.explanation ?: "", correctAnswerText(e))
        }
        _state.value = s.copy(feedback = fb)
    }

    fun next() {
        val s = _state.value
        val e = s.exercise ?: return
        val wasCorrect = s.feedback is Feedback.Correct
        if (!wasCorrect && e.id != null && e.id !in requeued) {
            requeued += e.id!!
            queue.addLast(e) // wrong exercises re-queue once at the end
        }
        queue.removeFirst()
        val nextEx = queue.firstOrNull()
        if (nextEx == null) {
            finish()
        } else {
            _state.value = s.copy(
                exercise = nextEx,
                position = s.position + 1,
                selectedOption = null,
                typedAnswer = "",
                feedback = null,
            )
        }
    }

    private fun finish() {
        val score = if (firstTryAnswered == 0) 1.0 else firstTryCorrect.toDouble() / firstTryAnswered
        val passed = !isCheckpoint || score >= 0.8
        viewModelScope.launch {
            if (isCheckpoint) {
                repo.recordCheckpointAttempt(unitNumber, passed, score)
                if (passed) repo.addXp(XP_PER_CHECKPOINT)
            } else {
                repo.completeLesson(unitNumber, lessonIndex ?: 0, score)
                repo.addXp(XP_PER_LESSON)
            }
        }
        _state.value = _state.value.copy(
            exercise = null,
            finished = true,
            correctCount = firstTryCorrect,
            answeredCount = firstTryAnswered,
            passed = passed,
        )
    }

    private fun correctAnswerText(e: ExerciseDto): String = when (e.type) {
        ExerciseTypes.MULTIPLE_CHOICE -> e.options?.getOrNull(e.correctIndex ?: 0).orEmpty()
        ExerciseTypes.FILL_BLANK -> e.answers?.firstOrNull().orEmpty()
        ExerciseTypes.TRANSLATION_IT_EN -> e.acceptedEn?.firstOrNull().orEmpty()
        ExerciseTypes.TRANSLATION_EN_IT -> e.acceptedIt?.firstOrNull().orEmpty()
        else -> ""
    }

    companion object {
        private const val TAG = "LessonViewModel"
        const val XP_PER_CORRECT = 5
        const val XP_PER_LESSON = 10
        const val XP_PER_CHECKPOINT = 50
        // Stage 1 renderers; listening/speaking/scramble arrive in Stage 3.
        val SUPPORTED_TYPES = setOf(
            ExerciseTypes.MULTIPLE_CHOICE,
            ExerciseTypes.FILL_BLANK,
            ExerciseTypes.TRANSLATION_IT_EN,
            ExerciseTypes.TRANSLATION_EN_IT,
        )
    }
}
