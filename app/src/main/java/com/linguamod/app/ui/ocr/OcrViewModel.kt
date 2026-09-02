package com.linguamod.app.ui.ocr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linguamod.app.data.CourseRepository
import com.linguamod.app.data.db.DictionaryEntryEntity
import com.linguamod.app.ocr.OcrAvailability
import com.linguamod.app.ocr.OcrBlock
import com.linguamod.app.ocr.OcrFrame
import com.linguamod.app.ocr.OcrGateway
import com.linguamod.app.ocr.OcrResult
import com.linguamod.app.ocr.OcrWordMatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * OCR camera (Stage 4 §1): camera preview → tap-to-scan → recognized blocks →
 * tap any word → bottom sheet with the dictionary entry (matched on lemma per
 * §9 normalization) or "not in your dictionary yet". Known-word taps award
 * [CourseRepository.XP_PER_OCR_LOOKUP] XP and increment the entry's lookup
 * counter.
 *
 * The recognized-blocks list + word-tap flow is fully gateway-driven: with the
 * scripted fake gateway no real camera frames are needed, so the whole flow is
 * testable on a headless emulator. Camera frames only feed the real gateway.
 */
@HiltViewModel
class OcrViewModel @Inject constructor(
    private val gateway: OcrGateway,
    private val repo: CourseRepository,
) : ViewModel() {

    /** One tappable recognized word. [context] is the block it was seen in. */
    data class RecognizedWord(val token: String, val context: String)

    sealed interface WordSheet {
        data class Known(val entry: DictionaryEntryEntity) : WordSheet
        data class Unknown(val token: String, val context: String) : WordSheet
    }

    data class UiState(
        val checking: Boolean = true,
        /** No Play Services — the screen explains once and offers only a way back. */
        val unavailable: Boolean = false,
        val permissionGranted: Boolean = false,
        val permissionDenied: Boolean = false,
        val scanning: Boolean = false,
        /** null = nothing scanned yet; empty = scan found no text. */
        val blocks: List<OcrBlock>? = null,
        val words: List<RecognizedWord> = emptyList(),
        /** Transient status line ("downloading text recognizer…", failures). */
        val info: String? = null,
        val sheet: WordSheet? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** Latest camera frame, fed by the screen's CameraX analyzer (nullable). */
    @Volatile
    private var latestFrame: OcrFrame? = null

    /** "downloading text recognizer…" is shown once per screen session (§1). */
    private var downloadMessageShown = false

    init {
        viewModelScope.launch {
            repo.initialize()
            _state.update {
                it.copy(
                    checking = false,
                    unavailable = gateway.availability() == OcrAvailability.NO_PLAY_SERVICES,
                )
            }
        }
    }

    fun onCameraFrame(frame: OcrFrame) {
        latestFrame = frame
    }

    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(permissionGranted = granted, permissionDenied = !granted) }
    }

    fun onScanClicked() {
        if (_state.value.scanning) return
        _state.update { it.copy(scanning = true, info = null) }
        viewModelScope.launch {
            when (val result = gateway.recognize(latestFrame)) {
                is OcrResult.Blocks -> {
                    val words = result.blocks.flatMap { block ->
                        OcrWordMatcher.tokens(block.text).map { RecognizedWord(it, block.text) }
                    }
                    _state.update {
                        it.copy(
                            scanning = false,
                            blocks = result.blocks,
                            words = words,
                            info = if (words.isEmpty()) "No text found — try again." else null,
                        )
                    }
                }
                OcrResult.ModelDownloading -> _state.update {
                    it.copy(
                        scanning = false,
                        // spec §1: show "downloading text recognizer…" once
                        info = if (downloadMessageShown) {
                            "The text recognizer is still downloading — try again in a moment."
                        } else {
                            downloadMessageShown = true
                            "downloading text recognizer…"
                        },
                    )
                }
                is OcrResult.Failed -> _state.update {
                    it.copy(
                        scanning = false,
                        info = "Couldn't read any text — point the camera at some text and tap Scan.",
                    )
                }
            }
        }
    }

    /** Word tap → lemma lookup; known words award 2 XP + a lookup increment. */
    fun onWordTapped(word: RecognizedWord) {
        viewModelScope.launch {
            val entry = OcrWordMatcher.lemmaCandidates(word.token)
                .firstNotNullOfOrNull { repo.findDictionaryEntryByLemma(it) }
            if (entry != null) {
                repo.recordOcrLookup(entry.id)
                _state.update { it.copy(sheet = WordSheet.Known(entry)) }
            } else {
                _state.update { it.copy(sheet = WordSheet.Unknown(word.token, word.context)) }
            }
        }
    }

    fun dismissSheet() {
        _state.update { it.copy(sheet = null) }
    }
}
