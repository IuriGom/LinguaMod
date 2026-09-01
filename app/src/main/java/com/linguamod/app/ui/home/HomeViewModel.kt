package com.linguamod.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linguamod.app.data.CourseRepository
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

data class HomeState(
    val loading: Boolean = true,
    val pluginLoaded: Boolean = false,
    val nodes: List<UnitNode> = emptyList(),
    val progress: UserProgressEntity? = null,
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
            // recompute when the plugin changes OR any progress row changes
            combine(repo.plugin, repo.lessonProgressFlow) { p, _ -> p }.collect { plugin ->
                refresh(plugin)
            }
        }
    }

    private suspend fun refresh(plugin: LinguaPluginDto?) {
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
        )
    }

    fun rescan() {
        viewModelScope.launch { repo.reload() }
    }
}
