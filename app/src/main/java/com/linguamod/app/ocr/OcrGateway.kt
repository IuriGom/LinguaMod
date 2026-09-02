package com.linguamod.app.ocr

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Test seam (Stage 4 §1, mirrors the Stage 3 audio gateways): all OCR feature
 * code consumes this interface, never ML Kit directly. Instrumented tests swap
 * in a scripted fake via Hilt (androidTest `fakes/FakeGateways.kt`).
 */
interface OcrGateway {
    /** Whether on-device text recognition can work on this device at all. */
    suspend fun availability(): OcrAvailability

    /**
     * Recognizes text in [frame]. A null frame is legal: fakes ignore it, the
     * real impl fails cleanly (no camera frame captured yet).
     */
    suspend fun recognize(frame: OcrFrame?): OcrResult
}

enum class OcrAvailability {
    /** Play Services present — the recognizer can be used. */
    READY,

    /** No Google Play Services — the feature hides entirely (one-time explanation). */
    NO_PLAY_SERVICES,
}

/** One recognized text block; words are tokenized per spec §9 in the UI layer. */
data class OcrBlock(val text: String)

sealed interface OcrResult {
    data class Blocks(val blocks: List<OcrBlock>) : OcrResult

    /** Play Services is still delivering the unbundled model — tell the user once. */
    data object ModelDownloading : OcrResult

    data class Failed(val reason: String) : OcrResult
}

/** One camera frame handed to the gateway (wraps ML Kit's [InputImage]). */
class OcrFrame(val inputImage: InputImage)

/**
 * Real gateway (Stage 4 §1): ML Kit Text Recognition v2, Latin script,
 * UNBUNDLED (com.google.android.gms:play-services-mlkit-text-recognition) —
 * the model is delivered by Play Services, never packaged in the APK.
 */
class MlKitOcrGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : OcrGateway {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun availability(): OcrAvailability {
        val code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        return if (code == ConnectionResult.SUCCESS) OcrAvailability.READY
        else OcrAvailability.NO_PLAY_SERVICES
    }

    override suspend fun recognize(frame: OcrFrame?): OcrResult {
        val image = frame?.inputImage ?: return OcrResult.Failed("no camera frame")
        return suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { vision ->
                    if (cont.isActive) {
                        cont.resume(OcrResult.Blocks(vision.textBlocks.map { OcrBlock(it.text) }))
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "recognition failed", e)
                    // UNAVAILABLE = the unbundled model hasn't downloaded yet.
                    val downloading =
                        (e as? com.google.mlkit.common.MlKitException)?.errorCode ==
                            com.google.mlkit.common.MlKitException.UNAVAILABLE
                    if (cont.isActive) {
                        cont.resume(
                            if (downloading) OcrResult.ModelDownloading
                            else OcrResult.Failed(e.message ?: "recognition failed"),
                        )
                    }
                }
        }
    }

    companion object { private const val TAG = "MlKitOcrGateway" }
}
