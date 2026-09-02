package com.linguamod.app.ui.lesson

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linguamod.app.audio.RecognitionOutcome
import com.linguamod.app.audio.SpeakingSubstitution
import com.linguamod.app.audio.SpeechRecognizerGateway
import com.linguamod.app.data.CourseRepository
import com.linguamod.app.plugin.AnswerMatcher
import com.linguamod.app.plugin.ExerciseDto
import com.linguamod.app.plugin.ExerciseTypes
import com.linguamod.app.plugin.SpeakingScorer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface Feedback {
    val explanation: String

    /**
     * @param heardLabel/ heard text revealed only after answering (Stage 3):
     *   listening → "You heard:" + speakIt; speaking → "Google heard:" + transcript.
     * @param heardNote speaking-only line distinguishing a pronunciation issue
     *   from the recognizer hearing something completely different.
     */
    data class Correct(
        override val explanation: String,
        val heardLabel: String? = null,
        val heard: String? = null,
        val heardNote: String? = null,
    ) : Feedback

    data class Wrong(
        override val explanation: String,
        val correctAnswer: String,
        val heardLabel: String? = null,
        val heard: String? = null,
        val heardNote: String? = null,
    ) : Feedback
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
    val hearts: Int = CourseRepository.MAX_HEARTS,
    val xpGained: Int = 0,
    /** Feature keys whose unlock snackbar has not been shown yet (checkpoint pass). */
    val newUnlocks: List<String> = emptyList(),
    /** sentence_scramble: tokens still in the bank / already placed (Stage 3 §4). */
    val scrambleBank: List<String> = emptyList(),
    val scramblePlaced: List<String> = emptyList(),
    /** speaking: a recognition attempt is in flight. */
    val speakingBusy: Boolean = false,
    /** speaking: transient "didn't catch that" message (retry, no attempt consumed). */
    val speakingError: String? = null,
    /** One-time-per-session cue that a speaking exercise became a listening one. */
    val showSpeakingSubstitution: Boolean = false,
) {
    val score: Double get() = if (answeredCount == 0) 0.0 else correctCount.toDouble() / answeredCount
}

/** Lesson engine: one exercise at a time; wrong exercises re-queue once at the end.
 *  Shared by normal lessons and checkpoints. All seven exercise types render
 *  (Stage 3); a speaking exercise is silently substituted with a listening-type
 *  variant when the recognizer is unavailable, so lessons stay completable. */
@HiltViewModel
class LessonViewModel @Inject constructor(
    private val repo: CourseRepository,
    private val speechGateway: SpeechRecognizerGateway,
    private val speakingSubstitution: SpeakingSubstitution,
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
            // hearts display follows the user progress row
            repo.userProgressFlow.collect { p ->
                _state.value = _state.value.copy(hearts = p?.hearts ?: CourseRepository.MAX_HEARTS)
            }
        }
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
            val first = queue.firstOrNull()?.let { enterExercise(it) }
            _state.value = LessonState(
                loading = false,
                title = unit?.let { "Unit ${it.number} — ${it.title}" } ?: "",
                isCheckpoint = isCheckpoint,
                exercise = first?.first,
                total = presentable.size,
                hearts = _state.value.hearts, // keep the live hearts value
                scrambleBank = first?.second.orEmpty(),
                showSpeakingSubstitution = _state.value.showSpeakingSubstitution,
            )
            if (presentable.isEmpty()) finish() // nothing presentable: nothing to fail
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

    // --- sentence_scramble (§6.7): tap-to-place, tap-to-remove ---

    fun placeScrambleToken(bankIndex: Int) {
        val s = _state.value
        if (s.feedback != null) return
        val token = s.scrambleBank.getOrNull(bankIndex) ?: return
        _state.value = s.copy(
            scrambleBank = s.scrambleBank.toMutableList().also { it.removeAt(bankIndex) },
            scramblePlaced = s.scramblePlaced + token,
        )
    }

    fun removeScrambleToken(placedIndex: Int) {
        val s = _state.value
        if (s.feedback != null) return
        val token = s.scramblePlaced.getOrNull(placedIndex) ?: return
        _state.value = s.copy(
            scramblePlaced = s.scramblePlaced.toMutableList().also { it.removeAt(placedIndex) },
            scrambleBank = s.scrambleBank + token,
        )
    }

    // --- speaking (§6.6) ---

    fun isMicPermissionGranted(): Boolean = speechGateway.isMicPermissionGranted()

    /** Mic tap: one recognition attempt; the result grades the exercise directly. */
    fun startSpeaking() {
        val s = _state.value
        val e = s.exercise ?: return
        if (e.type != ExerciseTypes.SPEAKING || s.feedback != null || s.speakingBusy) return
        _state.value = s.copy(speakingBusy = true, speakingError = null)
        viewModelScope.launch {
            when (val outcome = speechGateway.listen(e.targetIt.orEmpty())) {
                is RecognitionOutcome.Heard -> {
                    val r = SpeakingScorer.score(e.targetIt.orEmpty(), outcome.transcript, e.minAccuracy)
                    val heardNote = when {
                        r.passed -> null
                        r.completelyDifferent ->
                            "The recognizer heard something completely different — try a quieter room."
                        else ->
                            "Close! This looks like a pronunciation issue — listen and match the sounds."
                    }
                    grade(
                        e, r.passed,
                        if (r.passed) {
                            Feedback.Correct(e.explanation ?: "", "Google heard:", outcome.transcript)
                        } else {
                            Feedback.Wrong(
                                e.explanation ?: "", e.targetIt.orEmpty(),
                                "Google heard:", outcome.transcript, heardNote,
                            )
                        },
                    )
                }
                // availability lost mid-lesson → same silent substitution contract
                RecognitionOutcome.Unavailable, RecognitionOutcome.PermissionDenied ->
                    substituteCurrentSpeaking()
                is RecognitionOutcome.Failed -> _state.value = _state.value.copy(
                    speakingBusy = false,
                    speakingError = "Didn't catch that — tap the mic and try again.",
                )
            }
        }
    }

    /** Runtime mic denial: silently swap this speaking exercise for its listening variant. */
    fun substituteCurrentSpeaking() {
        val e = _state.value.exercise ?: return
        if (e.type != ExerciseTypes.SPEAKING) return
        val (ex, _) = enterExercise(e, forceSubstitute = true)
        _state.value = _state.value.copy(exercise = ex, speakingBusy = false)
    }

    /** The substitution snackbar was displayed; clear the cue. */
    fun clearSpeakingSubstitutionNotice() {
        if (_state.value.showSpeakingSubstitution) {
            _state.value = _state.value.copy(showSpeakingSubstitution = false)
        }
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
            ExerciseTypes.LISTENING -> when (e.mode) {
                "type" -> AnswerMatcher.matchesAny(e.acceptedIt.orEmpty(), s.typedAnswer)
                else -> s.selectedOption == e.correctIndex
            }
            ExerciseTypes.SENTENCE_SCRAMBLE ->
                normalizeWhitespace(s.scramblePlaced.joinToString(" ")) ==
                    normalizeWhitespace(e.correctSentence.orEmpty())
            else -> false
        }
        val feedback = when (e.type) {
            // listening always reveals what was spoken (§6.5) plus the explanation
            ExerciseTypes.LISTENING -> listeningFeedback(e, correct)
            else -> plainFeedback(e, correct)
        }
        grade(e, correct, feedback)
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
        val nextRaw = queue.firstOrNull()
        if (nextRaw == null) {
            finish()
        } else {
            val (nextEx, bank) = enterExercise(nextRaw)
            _state.value = s.copy(
                exercise = nextEx,
                position = s.position + 1,
                selectedOption = null,
                typedAnswer = "",
                feedback = null,
                scrambleBank = bank,
                scramblePlaced = emptyList(),
                speakingBusy = false,
                speakingError = null,
            )
        }
    }

    private fun finish() {
        val score = if (firstTryAnswered == 0) 1.0 else firstTryCorrect.toDouble() / firstTryAnswered
        val passed = !isCheckpoint || score >= 0.8
        val bonus = if (isCheckpoint) (if (passed) XP_PER_CHECKPOINT else 0) else XP_PER_LESSON
        viewModelScope.launch {
            val unlocks = if (isCheckpoint) {
                val newly = repo.recordCheckpointAttempt(unitNumber, passed, score)
                if (passed) repo.addXp(XP_PER_CHECKPOINT)
                newly
            } else {
                repo.completeLesson(unitNumber, lessonIndex ?: 0, score)
                repo.addXp(XP_PER_LESSON)
                emptyList()
            }
            _state.value = _state.value.copy(newUnlocks = unlocks)
        }
        _state.value = _state.value.copy(
            exercise = null,
            finished = true,
            correctCount = firstTryCorrect,
            answeredCount = firstTryAnswered,
            passed = passed,
            xpGained = firstTryCorrect * XP_PER_CORRECT + bonus,
        )
    }

    /** Persist that the unlock snackbar was displayed (shown exactly once). */
    fun markUnlocksShown() {
        val keys = _state.value.newUnlocks
        if (keys.isEmpty()) return
        viewModelScope.launch { keys.forEach { repo.markUnlockShown(it) } }
    }

    /**
     * Prepares [e] for display: speaking availability is checked BEFORE showing
     * (§6.6 contract) and the scramble bank is shuffled on open. Returns the
     * exercise to render plus the initial scramble bank.
     */
    private fun enterExercise(e: ExerciseDto, forceSubstitute: Boolean = false): Pair<ExerciseDto, List<String>> {
        var ex = e
        if (ex.type == ExerciseTypes.SPEAKING &&
            (forceSubstitute || !speechGateway.isRecognitionAvailable())
        ) {
            Log.i(TAG, "substituting speaking exercise ${ex.id} with a listening variant (recognizer unavailable)")
            if (speakingSubstitution.recordAndShouldNotify()) {
                _state.value = _state.value.copy(showSpeakingSubstitution = true)
            }
            ex = ex.copy(
                type = ExerciseTypes.LISTENING,
                prompt = "Listen and type what you hear.",
                mode = "type",
                speakIt = ex.targetIt,
                acceptedIt = listOfNotNull(ex.targetIt),
            )
        }
        val bank = if (ex.type == ExerciseTypes.SENTENCE_SCRAMBLE) {
            ex.tokens.orEmpty().shuffled(Random(ex.id.hashCode()))
        } else {
            emptyList()
        }
        return ex to bank
    }

    /** Shared per-attempt bookkeeping: first-try stats, result row, XP/hearts. */
    private fun grade(e: ExerciseDto, correct: Boolean, feedback: Feedback) {
        val isRetry = e.id in requeued
        if (!isRetry) {
            firstTryAnswered++
            if (correct) firstTryCorrect++
        }
        viewModelScope.launch { repo.recordExerciseResult(e.id!!, correct) }
        if (correct) viewModelScope.launch { repo.addXp(XP_PER_CORRECT) }
        else viewModelScope.launch { repo.loseHeart() } // hearts never block, just reflect
        _state.value = _state.value.copy(feedback = feedback, speakingBusy = false)
    }

    private fun plainFeedback(e: ExerciseDto, correct: Boolean): Feedback =
        if (correct) Feedback.Correct(e.explanation ?: "")
        else Feedback.Wrong(e.explanation ?: "", correctAnswerText(e))

    /** Listening feedback always reveals what was spoken (§6.5) plus the explanation. */
    private fun listeningFeedback(e: ExerciseDto, correct: Boolean): Feedback =
        if (correct) {
            Feedback.Correct(e.explanation ?: "", "You heard:", e.speakIt.orEmpty())
        } else {
            Feedback.Wrong(e.explanation ?: "", correctAnswerText(e), "You heard:", e.speakIt.orEmpty())
        }

    private fun correctAnswerText(e: ExerciseDto): String = when (e.type) {
        ExerciseTypes.MULTIPLE_CHOICE -> e.options?.getOrNull(e.correctIndex ?: 0).orEmpty()
        ExerciseTypes.FILL_BLANK -> e.answers?.firstOrNull().orEmpty()
        ExerciseTypes.TRANSLATION_IT_EN -> e.acceptedEn?.firstOrNull().orEmpty()
        ExerciseTypes.TRANSLATION_EN_IT -> e.acceptedIt?.firstOrNull().orEmpty()
        ExerciseTypes.LISTENING -> when (e.mode) {
            "type" -> e.acceptedIt?.firstOrNull() ?: e.speakIt.orEmpty()
            else -> e.options?.getOrNull(e.correctIndex ?: 0).orEmpty()
        }
        ExerciseTypes.SPEAKING -> e.targetIt.orEmpty()
        ExerciseTypes.SENTENCE_SCRAMBLE -> e.correctSentence.orEmpty()
        else -> ""
    }

    companion object {
        private const val TAG = "LessonViewModel"
        const val XP_PER_CORRECT = 5
        const val XP_PER_LESSON = 10
        const val XP_PER_CHECKPOINT = 50

        /** Whitespace-normalized comparison for sentence_scramble (§6.7). */
        fun normalizeWhitespace(s: String): String = s.trim().replace(Regex("\\s+"), " ")

        // All seven types render as of Stage 3.
        val SUPPORTED_TYPES = ExerciseTypes.ALL.toSet()
    }
}
