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
    /** Story entries on the path (Stage 4 §2), anchored after their unit's node. */
    val stories: List<StoryEntry> = emptyList(),
    /** Boss overlays after every 5th unit (Stage 4 §3). */
    val bosses: List<BossEntry> = emptyList(),
    /** Mixed Practice card on Home (Stage 4 §4), visible only when unlocked. */
    val practiceUnlocked: Boolean = false,
)

/**
 * A story's book icon on the Home path. Shown once its anchor unit is
 * reachable (or the story flag has tripped); locked entries show the unlock
 * condition. Placeholders (nodes: []) render in the player as a
 * "future content pack" message.
 */
data class StoryEntry(
    val id: String,
    val title: String,
    val afterUnit: Int,
    val unlocked: Boolean,
    val completed: Boolean,
    /** Anchor unit absent from this plugin: the row parks at the end of the path. */
    val anchored: Boolean,
)

/** A boss overlay on the path (Stage 4 §3), themed after every 5th unit. */
data class BossEntry(
    val unit: Int,
    val unlocked: Boolean,
    val won: Boolean,
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
            // recompute when the plugin, progress rows, review items, or badges change
            combine(
                repo.plugin, repo.lessonProgressFlow, repo.reviewItemsFlow,
                repo.badgesFlow, repo.bossResultsFlow,
            ) { p, rows, reviews, badges, bossResults ->
                Data(
                    p, rows, reviews,
                    badges.map { it.badgeId }.toSet(),
                    bossResults.filter { it.won }.map { it.bossId }.toSet(),
                )
            }.collect { d -> refresh(d.plugin, d.rows, d.reviews, d.badgeIds, d.wonBossIds) }
        }
    }

    private data class Data(
        val plugin: LinguaPluginDto?,
        val rows: List<LessonProgressEntity>,
        val reviews: List<com.linguamod.app.data.db.ReviewItemEntity>,
        val badgeIds: Set<String>,
        val wonBossIds: Set<String>,
    )

    private suspend fun refresh(
        plugin: LinguaPluginDto?,
        rows: List<LessonProgressEntity>,
        reviews: List<com.linguamod.app.data.db.ReviewItemEntity>,
        badgeIds: Set<String>,
        wonBossIds: Set<String>,
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
        val unitNumbers = plugin.units.mapNotNull { it.number }.toSet()
        val stories = plugin.stories.mapNotNull { s ->
            val id = s.id ?: return@mapNotNull null
            val afterUnit = s.unlockAfterUnit ?: return@mapNotNull null
            val anchored = afterUnit in unitNumbers
            val flagTripped = repo.isFeatureUnlocked(id)
            // Hidden until the anchor unit is reachable or the flag has tripped
            // (gating regression: no entry points before their unlock condition).
            if (!flagTripped && !(anchored && repo.isUnitUnlocked(afterUnit))) return@mapNotNull null
            StoryEntry(
                id = id,
                title = s.title ?: id,
                afterUnit = afterUnit,
                unlocked = flagTripped,
                completed = com.linguamod.app.core.Badges.narratoreId(id) in badgeIds,
                anchored = anchored,
            )
        }
        // Boss overlays after every 5th unit present in the plugin (§3). Same
        // visibility rule as stories: hidden until reachable or flag-tripped.
        val bossFlag = repo.isFeatureUnlocked(com.linguamod.app.data.FeatureUnlocks.BOSS_BATTLES)
        val bosses = plugin.units.mapNotNull { u ->
            val n = u.number ?: return@mapNotNull null
            if (n % 5 != 0) return@mapNotNull null
            if (!bossFlag && !repo.isUnitUnlocked(n)) return@mapNotNull null
            BossEntry(unit = n, unlocked = bossFlag, won = "boss_$n" in wonBossIds)
        }
        _state.value = HomeState(
            loading = false,
            pluginLoaded = true,
            nodes = nodes,
            progress = repo.userProgress(),
            bars = computeBars(plugin, rows),
            reviewDue = repo.dueReviewCount(reviews),
            stories = stories,
            bosses = bosses,
            practiceUnlocked = repo.isFeatureUnlocked(com.linguamod.app.data.FeatureUnlocks.MIXED_PRACTICE),
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
