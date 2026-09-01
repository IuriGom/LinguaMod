package com.linguamod.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linguamod.app.core.Levels
import com.linguamod.app.data.CourseRepository
import com.linguamod.app.data.ThemeCatalog
import com.linguamod.app.data.ThemeStore
import com.linguamod.app.data.db.AppDatabase
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
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repo: CourseRepository,
    private val db: AppDatabase,
    private val themeStore: ThemeStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state

    init {
        viewModelScope.launch {
            repo.initialize()
            db.progressDao().observeUserProgress().collect { p ->
                _state.value = _state.value.copy(progress = p ?: UserProgressEntity())
            }
        }
        viewModelScope.launch {
            // level + completed units derive from lesson progress rows
            repo.lessonProgressFlow.collect {
                val highest = db.progressDao().highestCompletedCheckpoint()
                val level = Levels.levelFor(highest)
                _state.value = _state.value.copy(
                    unitsCompleted = db.progressDao().countCompletedCheckpoints(),
                    level = level,
                    highestCheckpoint = highest ?: 0,
                    nextLevelThreshold = Levels.nextThreshold(level),
                )
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
