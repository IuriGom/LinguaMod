package com.linguamod.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linguamod.app.data.CourseRepository
import com.linguamod.app.data.db.AppDatabase
import com.linguamod.app.data.db.PluginMetaEntity
import com.linguamod.app.data.db.UserProgressEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ProfileState(
    val progress: UserProgressEntity = UserProgressEntity(),
    val unitsCompleted: Int = 0,
    val plugins: List<PluginMetaEntity> = emptyList(),
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repo: CourseRepository,
    private val db: AppDatabase,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state

    init {
        viewModelScope.launch {
            repo.initialize()
            db.progressDao().observeUserProgress().collect { p ->
                _state.value = _state.value.copy(
                    progress = p ?: UserProgressEntity(),
                    unitsCompleted = db.progressDao().countCompletedCheckpoints(),
                )
            }
        }
        viewModelScope.launch {
            db.pluginMetaDao().observeAll().collect { plugins ->
                _state.value = _state.value.copy(plugins = plugins)
            }
        }
    }

    fun rescan() {
        viewModelScope.launch { repo.reload() }
    }
}
