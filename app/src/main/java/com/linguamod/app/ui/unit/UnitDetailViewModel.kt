package com.linguamod.app.ui.unit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linguamod.app.data.CourseRepository
import com.linguamod.app.data.db.LessonProgressEntity
import com.linguamod.app.plugin.LessonDto
import com.linguamod.app.plugin.UnitDto
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class LessonRow(
    val index: Int, // 0-3, 4 = checkpoint
    val title: String,
    val unlocked: Boolean,
    val completed: Boolean,
    val isCheckpoint: Boolean,
)

data class UnitDetailState(
    val loading: Boolean = true,
    val unit: UnitDto? = null,
    val rows: List<LessonRow> = emptyList(),
)

@HiltViewModel
class UnitDetailViewModel @Inject constructor(
    private val repo: CourseRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val unitNumber: Int = checkNotNull(savedState["unit"])

    private val _state = MutableStateFlow(UnitDetailState())
    val state: StateFlow<UnitDetailState> = _state

    init {
        viewModelScope.launch {
            repo.initialize()
            combine(repo.plugin, repo.lessonProgressFlow) { p, _ -> p }.collect { plugin ->
                val unit = plugin?.units?.firstOrNull { it.number == unitNumber }
                if (unit == null) {
                    _state.value = UnitDetailState(loading = false)
                    return@collect
                }
                val rows = mutableListOf<LessonRow>()
                unit.lessons?.forEachIndexed { i, l ->
                    rows += LessonRow(
                        index = i,
                        title = l.title ?: "Lesson ${i + 1}",
                        unlocked = repo.isLessonUnlocked(unitNumber, i),
                        completed = repo.isLessonCompleted(unitNumber, i),
                        isCheckpoint = false,
                    )
                }
                rows += LessonRow(
                    index = CourseRepository.CHECKPOINT_INDEX,
                    title = "Checkpoint",
                    unlocked = repo.isCheckpointUnlocked(unitNumber),
                    completed = repo.isLessonCompleted(unitNumber, CourseRepository.CHECKPOINT_INDEX),
                    isCheckpoint = true,
                )
                _state.value = UnitDetailState(loading = false, unit = unit, rows = rows)
            }
        }
    }
}
