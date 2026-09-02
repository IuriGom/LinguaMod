package com.linguamod.app.ui.flashcard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linguamod.app.data.CourseRepository
import com.linguamod.app.data.db.DictionaryEntryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class FlashcardState(
    val loading: Boolean = true,
    /** Deck was empty (no units started yet). */
    val empty: Boolean = false,
    val current: DictionaryEntryEntity? = null,
    val flipped: Boolean = false,
    val position: Int = 0, // cards retired so far
    val total: Int = 0,
    val finished: Boolean = false,
)

/** Flashcards (Stage 3 §6): deck = entries from started units, session of 20,
 *  self-graded, "Still learning" re-queues in-session. Independent of the SRS. */
@HiltViewModel
class FlashcardViewModel @Inject constructor(
    private val repo: CourseRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FlashcardState())
    val state: StateFlow<FlashcardState> = _state

    private var session: FlashcardSession? = null

    init {
        viewModelScope.launch {
            repo.initialize()
            var plugin = repo.plugin.value
            while (plugin == null) { kotlinx.coroutines.delay(50); plugin = repo.plugin.value }
            val s = FlashcardSession(repo.flashcardEntries())
            session = s
            _state.value = FlashcardState(
                loading = false,
                empty = s.size == 0,
                current = s.current,
                total = s.size,
                finished = s.isExhausted,
            )
        }
    }

    fun flip() {
        if (_state.value.current != null) {
            _state.value = _state.value.copy(flipped = !_state.value.flipped)
        }
    }

    fun gradeGotIt() = advance(gotIt = true)

    fun gradeStillLearning() = advance(gotIt = false)

    private fun advance(gotIt: Boolean) {
        val s = session ?: return
        if (_state.value.finished) return
        if (gotIt) s.gradeGotIt() else s.gradeStillLearning()
        _state.value = _state.value.copy(
            current = s.current,
            flipped = false,
            position = s.gradedGotIt,
            finished = s.isExhausted,
        )
    }
}
