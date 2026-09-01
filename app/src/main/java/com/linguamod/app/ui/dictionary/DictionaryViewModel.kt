package com.linguamod.app.ui.dictionary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linguamod.app.data.CourseRepository
import com.linguamod.app.data.db.DictionaryEntryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DictionaryViewModel @Inject constructor(
    private val repo: CourseRepository,
    private val db: com.linguamod.app.data.db.AppDatabase,
) : ViewModel() {

    private val query = MutableStateFlow("")

    // Only entries from units the user has started (highest unlocked unit).
    val entries: StateFlow<List<DictionaryEntryEntity>> = repo.plugin
        .flatMapLatest { plugin ->
            val maxUnit = highestStartedUnit()
            db.dictionaryDao().observeUpToUnit(maxUnit)
        }
        .combine(query) { list, q ->
            if (q.isBlank()) list
            else list.filter {
                it.word.contains(q, true) || it.translation.contains(q, true)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** A unit counts as started once the user has opened any of its lessons. */
    private suspend fun highestStartedUnit(): Int {
        val progress = db.progressDao().getAllLessonProgress()
        val startedUnits = progress.map { it.unitNumber }.toSet()
        val unitNumbers = repo.plugin.value?.units?.mapNotNull { it.number } ?: listOf(1)
        return unitNumbers.filter { it in startedUnits }.maxOrNull() ?: 0
    }

    fun setQuery(q: String) { query.value = q }

    init {
        viewModelScope.launch { repo.initialize() }
    }
}
