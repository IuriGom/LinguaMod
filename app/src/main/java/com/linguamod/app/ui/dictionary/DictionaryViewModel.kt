package com.linguamod.app.ui.dictionary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linguamod.app.data.CourseRepository
import com.linguamod.app.data.FeatureUnlocks
import com.linguamod.app.data.db.DictionaryEntryEntity
import com.linguamod.app.ocr.OcrAvailability
import com.linguamod.app.ocr.OcrGateway
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
    private val ocrGateway: OcrGateway,
    private val featureUnlocks: FeatureUnlocks,
) : ViewModel() {

    private val query = MutableStateFlow("")

    // Only entries from units the user has started (highest unlocked unit).
    val entries: StateFlow<List<DictionaryEntryEntity>> = repo.plugin
        .flatMapLatest { plugin ->
            val maxUnit = repo.highestStartedUnit()
            db.dictionaryDao().observeUpToUnit(maxUnit)
        }
        .combine(query) { list, q ->
            if (q.isBlank()) list
            else list.filter {
                it.word.contains(q, true) || it.translation.contains(q, true)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Play Services presence, checked once (OCR is unusable without it, §1). */
    private val ocrGatewayAvailable = MutableStateFlow(true)

    /**
     * Camera-OCR entry point on this screen: visible only when the ocrCamera
     * flag has tripped AND the device can actually recognize text. On a device
     * without Play Services the feature hides entirely (§1).
     */
    val ocrIconVisible: StateFlow<Boolean> =
        combine(repo.featureFlagsFlow, ocrGatewayAvailable) { flags, available ->
            FeatureUnlocks.OCR_CAMERA in flags && available
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _showOcrUnavailableExplanation = MutableStateFlow(false)
    val showOcrUnavailableExplanation: StateFlow<Boolean> = _showOcrUnavailableExplanation

    init {
        viewModelScope.launch { repo.initialize() }
        viewModelScope.launch {
            val available = ocrGateway.availability() == OcrAvailability.READY
            ocrGatewayAvailable.value = available
            // one-time explanation, then the feature hides entirely (§1)
            if (!available && featureUnlocks.isUnlocked(FeatureUnlocks.OCR_CAMERA) &&
                !featureUnlocks.wasShown(OCR_UNAVAILABLE_SHOWN_KEY)
            ) {
                _showOcrUnavailableExplanation.value = true
            }
        }
    }

    fun dismissOcrUnavailableExplanation() {
        viewModelScope.launch {
            featureUnlocks.markShown(OCR_UNAVAILABLE_SHOWN_KEY)
            _showOcrUnavailableExplanation.value = false
        }
    }

    fun setQuery(q: String) { query.value = q }

    companion object {
        private const val OCR_UNAVAILABLE_SHOWN_KEY = "ocr_unavailable_explained"
    }
}
