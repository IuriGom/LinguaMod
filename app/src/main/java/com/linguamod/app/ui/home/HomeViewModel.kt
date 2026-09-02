package com.linguamod.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linguamod.app.core.Levels
import com.linguamod.app.data.CourseRepository
import com.linguamod.app.data.db.LessonProgressEntity
import com.linguamod.app.data.db.UserProgressEntity
import com.linguamod.app.plugin.LinguaPluginDto
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class UnitNode(
    val number: Int,
    val title: String,
    val unlocked: Boolean,
    val completed: Boolean,
    val isCurrent: Boolean,
)

/** Three progress bars on Home (Stage 2B §7): current unit, current phase, total course. */
data class ProgressBars(
    val unitPct: Float = 0f,
    val phasePct: Float = 0f,
    val totalPct: Float = 0f,
)

data class HomeState(
    val loading: Boolean = true,
    val pluginLoaded: Boolean = false,
    val nodes: List<UnitNode> = emptyList(),
    val progress: UserProgressEntity? = null,
    val bars: ProgressBars = ProgressBars(),
    /** Due review items (Stage 3 §5); the review card shows only when > 0. */
    val reviewDue: Int = 0,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: CourseRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state

    init {
        viewModelScope.launch {
            repo.initialize()
            // recompute when the plugin, progress rows, or review items change
            combine(repo.plugin, repo.lessonProgressFlow, repo.reviewItemsFlow) { p, rows, reviews ->
                Triple(p, rows, reviews)
            }.collect { (plugin, rows, reviews) -> refresh(plugin, rows, reviews) }
        }
    }

    private suspend fun refresh(
        plugin: LinguaPluginDto?,
        rows: List<LessonProgressEntity>,
        reviews: List<com.linguamod.app.data.db.ReviewItemEntity>,
    ) {
        if (plugin == null) {
            _state.value = HomeState(loading = false, pluginLoaded = false)
            return
        }
        val nodes = plugin.units.map { u ->
            val n = u.number ?: 0
            val unlocked = repo.isUnitUnlocked(n)
            val completed = repo.isUnitUnlocked(n + 1)
            UnitNode(
                number = n,
                title = u.title ?: "Unit $n",
                unlocked = unlocked,
                completed = completed,
                isCurrent = unlocked && !completed,
            )
        }
        _state.value = HomeState(
            loading = false,
            pluginLoaded = true,
            nodes = nodes,
            progress = repo.userProgress(),
            bars = computeBars(plugin, rows),
            reviewDue = repo.dueReviewCount(reviews),
        )
    }

    /** Bar percentages from real LessonProgress rows; tolerates short plugins. */
    private fun computeBars(plugin: LinguaPluginDto, rows: List<LessonProgressEntity>): ProgressBars {
        val totalUnits = plugin.units.size.coerceAtLeast(1)
        fun checkpointDone(unit: Int) =
            rows.any { it.unitNumber == unit && it.lessonIndex == CourseRepository.CHECKPOINT_INDEX && it.completed }
        val completedUnits = plugin.units.count { checkpointDone(it.number ?: 0) }

        // current unit = first unit without a passed checkpoint (last when course finished)
        val currentUnit = plugin.units.firstOrNull { !checkpointDone(it.number ?: 0) }?.number
            ?: plugin.units.last().number ?: 1
        val unitDone = rows.count {
            it.unitNumber == currentUnit && it.completed
        } // lessons 0-3 + checkpoint = 5 rows max
        val unitPct = (unitDone / 5f).coerceIn(0f, 1f)

        val phase = Levels.phaseFor(currentUnit)
        val range = Levels.phaseRange(phase, totalUnits)
        val phaseDone = plugin.units.count { (it.number ?: 0) in range && checkpointDone(it.number ?: 0) }
        val phasePct = (phaseDone.toFloat() / (range.last - range.first + 1)).coerceIn(0f, 1f)

        return ProgressBars(
            unitPct = unitPct,
            phasePct = phasePct,
            totalPct = (completedUnits.toFloat() / totalUnits).coerceIn(0f, 1f),
        )
    }

    fun rescan() {
        viewModelScope.launch { repo.reload() }
    }
}
