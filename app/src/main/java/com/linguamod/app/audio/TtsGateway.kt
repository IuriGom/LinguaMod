package com.linguamod.app.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * Test seam (Stage 3 §0): every audio feature consumes this interface, never
 * android.speech.tts directly. Instrumented tests swap in a fake via Hilt.
 */
interface TtsGateway {
    /** True when the TTS engine is initialized and Italian voice data is present. */
    suspend fun isItalianVoiceAvailable(): Boolean

    /** Speaks [text] in Italian at [speechRate] (1.0 = normal). True on success. */
    suspend fun speak(text: String, speechRate: Float = 1.0f): Boolean

    /** Synthesizes [text] (Italian) into [outFile]. True on success. */
    suspend fun synthesizeToFile(text: String, outFile: File): Boolean

    fun release()
}

/** Real gateway: wraps [TextToSpeech] with [Locale.ITALIAN]. */
class AndroidTtsGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : TtsGateway {

    @Volatile
    private var engine: TextToSpeech? = null

    @Volatile
    private var initDone = false

    @Volatile
    private var initOk = false

    private val initMutex = Mutex()

    /** Serializes speak/synthesize calls: one outstanding utterance at a time. */
    private val opMutex = Mutex()

    private suspend fun engine(): TextToSpeech? {
        if (initDone) return if (initOk) engine else null
        return initMutex.withLock {
            if (!initDone) {
                initOk = suspendCancellableCoroutine { cont ->
                    val t = TextToSpeech(context) { status ->
                        initDone = true
                        cont.resume(status == TextToSpeech.SUCCESS)
                    }
                    engine = t
                }
                if (initOk) engine?.setLanguage(Locale.ITALIAN)
            }
            if (initOk) engine else null
        }
    }

    override suspend fun isItalianVoiceAvailable(): Boolean {
        val t = engine() ?: return false
        return t.isLanguageAvailable(Locale.ITALIAN) >= TextToSpeech.LANG_AVAILABLE
    }

    override suspend fun speak(text: String, speechRate: Float): Boolean {
        val t = engine() ?: return false
        return opMutex.withLock {
            suspendCancellableCoroutine { cont ->
                val id = "lm-speak-${System.nanoTime()}"
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id && cont.isActive) cont.resume(true)
                    }

                    @Deprecated("deprecated in favor of onError with code")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == id && cont.isActive) cont.resume(false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == id && cont.isActive) cont.resume(false)
                    }
                })
                t.setSpeechRate(speechRate)
                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
                }
                if (t.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) {
                    if (cont.isActive) cont.resume(false)
                }
                cont.invokeOnCancellation { t.stop() }
            }
        }
    }

    override suspend fun synthesizeToFile(text: String, outFile: File): Boolean {
        val t = engine() ?: return false
        return opMutex.withLock {
            suspendCancellableCoroutine { cont ->
                val id = "lm-synth-${System.nanoTime()}"
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id && cont.isActive) cont.resume(true)
                    }

                    @Deprecated("deprecated in favor of onError with code")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == id && cont.isActive) cont.resume(false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == id && cont.isActive) cont.resume(false)
                    }
                })
                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
                }
                if (t.synthesizeToFile(text, params, outFile, id) != TextToSpeech.SUCCESS) {
                    if (cont.isActive) cont.resume(false)
                }
            }
        }
    }

    override fun release() {
        runCatching { engine?.stop(); engine?.shutdown() }
        engine = null
        initDone = false
        initOk = false
    }
}
