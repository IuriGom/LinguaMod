package com.linguamod.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linguamod.app.core.Levels
import com.linguamod.app.data.CourseRepository
import com.linguamod.app.data.FeatureUnlocks
import com.linguamod.app.data.ThemeCatalog
import com.linguamod.app.data.ThemeStore
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.data.db.DictionaryEntryEntity
import com.linguamod.app.data.db.PluginMetaEntity
import com.linguamod.app.data.db.UserProgressEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class ProfileState(
    val progress: UserProgressEntity = UserProgressEntity(),
    val unitsCompleted: Int = 0,
    val plugins: List<PluginMetaEntity> = emptyList(),
    val level: Int = 0,
    val highestCheckpoint: Int = 0,
    val nextLevelThreshold: Int? = Levels.PHASE_ENDS.first(),
    val unlockedBadges: Set<String> = emptySet(),
    val purchasedThemes: Set<String> = emptySet(),
    val activeTheme: String = ThemeCatalog.DEFAULT.id,
    // "Your Records" (Stage 4 §5): personal stats, gated by the leaderboards flag.
    val recordsVisible: Boolean = false,
    /** XP per day for the last 14 days, oldest first; zero-filled gaps. */
    val dailyXp: List<Pair<String, Int>> = emptyList(),
    /** Best checkpoint score per unit, unit-ascending. */
    val bestCheckpointScores: List<Pair<Int, Double>> = emptyList(),
    /** Most-looked-up dictionary entries (OCR lookups, part B increments). */
    val mostLookedUp: List<DictionaryEntryEntity> = emptyList(),
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repo: CourseRepository,
    private val db: AppDatabase,
    private val themeStore: ThemeStore,
    private val clock: com.linguamod.app.core.Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state

    init {
        viewModelScope.launch {
            repo.initialize()
            db.progressDao().observeUserProgress().collect { p ->
                _state.value = _state.value.copy(progress = p ?: UserProgressEntity())
                refreshRecords()
            }
        }
        viewModelScope.launch {
            // level + completed units derive from lesson progress rows
            repo.lessonProgressFlow.collect { rows ->
                val highest = db.progressDao().highestCompletedCheckpoint()
                val level = Levels.levelFor(highest)
                _state.value = _state.value.copy(
                    unitsCompleted = db.progressDao().countCompletedCheckpoints(),
                    level = level,
                    highestCheckpoint = highest ?: 0,
                    nextLevelThreshold = Levels.nextThreshold(level),
                    bestCheckpointScores = rows
                        .filter { it.lessonIndex == CourseRepository.CHECKPOINT_INDEX && it.completed }
                        .sortedBy { it.unitNumber }
                        .map { it.unitNumber to it.score },
                )
                refreshRecords()
            }
        }
        viewModelScope.launch {
            db.badgeDao().observeAll().collect { badges ->
                _state.value = _state.value.copy(unlockedBadges = badges.map { it.badgeId }.toSet())
            }
        }
        viewModelScope.launch {
            combine(themeStore.purchased, themeStore.activeTheme) { p, a -> p to a }.collect { (p, a) ->
                _state.value = _state.value.copy(purchasedThemes = p, activeTheme = a)
            }
        }
        viewModelScope.launch {
            db.pluginMetaDao().observeAll().collect { plugins ->
                _state.value = _state.value.copy(plugins = plugins)
            }
        }
    }

    /**
     * "Your Records" data (Stage 4 §5). Recomputed on progress/lesson changes.
     * Personal stats only — no server, no fabricated competitors.
     */
    private suspend fun refreshRecords() {
        val visible = repo.isFeatureUnlocked(FeatureUnlocks.LEADERBOARDS)
        if (!visible) {
            _state.value = _state.value.copy(recordsVisible = false)
            return
        }
        val byDate = repo.dailyXpLastDays(14).associate { it.date to it.xp }
        val today = clock.today()
        val days = (13 downTo 0).map { today.minusDays(it.toLong()).toString() }
        _state.value = _state.value.copy(
            recordsVisible = true,
            dailyXp = days.map { it to (byDate[it] ?: 0) },
            mostLookedUp = repo.mostLookedUpEntries(5).filter { it.lookupCount > 0 },
        )
    }

    fun buyTheme(themeId: String) {
        viewModelScope.launch { repo.purchaseTheme(themeId) }
    }

    fun applyTheme(themeId: String) {
        viewModelScope.launch { repo.selectTheme(themeId) }
    }

    fun rescan() {
        viewModelScope.launch { repo.reload() }
    }
}
