package com.linguamod.app.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Outcome of one recognition attempt (Stage 3 §3). */
sealed interface RecognitionOutcome {
    /** Final transcript; [partials] carries the streamed partial hypotheses. */
    data class Heard(val transcript: String, val partials: List<String> = emptyList()) : RecognitionOutcome

    /** No recognizer on device / offline without on-device model. */
    data object Unavailable : RecognitionOutcome

    /** RECORD_AUDIO denied. */
    data object PermissionDenied : RecognitionOutcome

    /** Recognizer ran but produced nothing usable (no match, timeout, audio error…). */
    data class Failed(val reason: String) : RecognitionOutcome
}

/**
 * Test seam (Stage 3 §0): feature code consumes this interface only.
 * Instrumented tests swap in a scripted fake via Hilt.
 */
interface SpeechRecognizerGateway {
    /** Recognizer present on the device (independent of mic permission). */
    fun isRecognitionAvailable(): Boolean

    /** RECORD_AUDIO currently granted. */
    fun isMicPermissionGranted(): Boolean

    /**
     * One listen attempt for [expectedPhrase] (a hint only — a recognizer never
     * sees it; the fake uses it as the default echo so the Solver Bot passes).
     */
    suspend fun listen(expectedPhrase: String): RecognitionOutcome
}

/** Real gateway: wraps [SpeechRecognizer], free-form Italian, partial results on. */
class AndroidSpeechRecognizerGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechRecognizerGateway {

    override fun isRecognitionAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    override fun isMicPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun listen(expectedPhrase: String): RecognitionOutcome {
        if (!isRecognitionAvailable()) return RecognitionOutcome.Unavailable
        if (!isMicPermissionGranted()) return RecognitionOutcome.PermissionDenied
        // SpeechRecognizer must be created and driven on the main thread.
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                if (recognizer == null) {
                    if (cont.isActive) cont.resume(RecognitionOutcome.Unavailable)
                    return@suspendCancellableCoroutine
                }
                val resumed = AtomicBoolean(false)
                val partials = mutableListOf<String>()

                fun finish(outcome: RecognitionOutcome) {
                    if (resumed.compareAndSet(false, true)) {
                        recognizer.destroy()
                        cont.resume(outcome)
                    }
                }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {
                        partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { partials += it }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}

                    override fun onResults(results: Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        if (text.isNullOrBlank()) {
                            finish(RecognitionOutcome.Failed("no match"))
                        } else {
                            finish(RecognitionOutcome.Heard(text, partials.toList()))
                        }
                    }

                    override fun onError(error: Int) {
                        finish(
                            when (error) {
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                    RecognitionOutcome.PermissionDenied
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                                    RecognitionOutcome.Failed("recognizer busy")
                                SpeechRecognizer.ERROR_NO_MATCH ->
                                    RecognitionOutcome.Failed("no match")
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                    RecognitionOutcome.Failed("speech timeout")
                                SpeechRecognizer.ERROR_NETWORK,
                                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                                -> RecognitionOutcome.Failed("network")
                                else -> RecognitionOutcome.Failed("error $error")
                            }
                        )
                    }
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                recognizer.startListening(intent)
                cont.invokeOnCancellation {
                    runCatching { recognizer.cancel(); recognizer.destroy() }
                }
            }
        }
    }
}
