package com.linguamod.app.ui.story

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linguamod.app.data.CourseRepository
import com.linguamod.app.plugin.StoryChoiceDto
import com.linguamod.app.plugin.StoryDto
import com.linguamod.app.plugin.StoryNodeDto
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class StoryState(
    val loading: Boolean = true,
    /** Story id not present in the loaded plugin (should not happen from Home). */
    val missing: Boolean = false,
    /** Registered with `nodes: []` — a future content pack will fill it in. */
    val placeholder: Boolean = false,
    val title: String = "",
    val node: StoryNodeDto? = null,
    val showEnglish: Boolean = false,
    /** Teaching feedback of the last wrong choice (the story loops on the node). */
    val feedback: String? = null,
    val completed: Boolean = false,
    /** True when this completion awarded XP (false on replays). */
    val xpAwarded: Boolean = false,
)

/**
 * Story player (spec §7, Stage 4 §2): node text with an optional English
 * toggle, a speaker label, and 2–3 choices. A correct choice advances; a
 * wrong one shows its teaching feedbackEn and loops on the same node. The
 * terminal node completes the story: 30 XP + the "Narratore" badge, once.
 */
@HiltViewModel
class StoryViewModel @Inject constructor(
    private val repo: CourseRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val storyId: String = savedState["storyId"] ?: ""

    private val _state = MutableStateFlow(StoryState())
    val state: StateFlow<StoryState> = _state

    private var story: StoryDto? = null

    init {
        viewModelScope.launch {
            repo.initialize()
            story = repo.plugin.value?.stories?.firstOrNull { it.id == storyId }
            val s = story
            _state.value = when {
                s == null -> StoryState(loading = false, missing = true)
                s.nodes.isEmpty() -> StoryState(loading = false, placeholder = true, title = s.title ?: storyId)
                else -> StoryState(loading = false, title = s.title ?: storyId, node = s.nodes.first())
            }
        }
    }

    fun choose(choice: StoryChoiceDto) {
        val s = _state.value
        val current = s.node ?: return
        if (s.completed) return
        if (!choice.correct) {
            // Wrong choice: teaching feedback, loop back on the same node.
            _state.value = s.copy(feedback = choice.feedbackEn ?: "")
            return
        }
        val next = story?.nodes?.firstOrNull { it.id == choice.next } ?: return
        _state.value = s.copy(node = next, feedback = null)
    }

    /** Terminal node's finish button: awards 30 XP + badge on first completion. */
    fun finishStory() {
        val s = _state.value
        if (s.completed || s.node?.terminal != true) return
        viewModelScope.launch {
            val awarded = repo.completeStoryOnce(storyId)
            _state.value = _state.value.copy(completed = true, xpAwarded = awarded)
        }
    }

    fun toggleEnglish() {
        _state.value = _state.value.copy(showEnglish = !_state.value.showEnglish)
    }

    /** Replayable: back to the first node; rewards are not re-awarded. */
    fun replay() {
        val first = story?.nodes?.firstOrNull() ?: return
        _state.value = _state.value.copy(
            node = first, feedback = null, completed = false, xpAwarded = false,
        )
    }
}
