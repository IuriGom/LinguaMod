package com.linguamod.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Stage 9: open-source OCR fallback (Tesseract via tesseract4android,
 * Apache-2.0) for devices without Google Play Services. The Italian
 * traineddata ships in assets/tessdata (tessdata_fast, 2.7 MB) and is copied
 * to internal storage on first use — fully offline, no download, no telemetry.
 *
 * Frames arrive as ML Kit [com.google.mlkit.vision.common.InputImage] wrappers
 * (see [OcrFrame]); the media image (YUV_420_888 from CameraX) is converted
 * to a rotation-corrected Bitmap for Tesseract.
 */
class TessOcrGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : OcrGateway {

    private val apiMutex = Mutex()

    @Volatile
    private var api: TessBaseAPI? = null

    @Volatile
    private var initFailed = false

    /** Tesseract is bundled — always usable once traineddata is installed. */
    override suspend fun availability(): OcrAvailability =
        if (engine() != null) OcrAvailability.READY else OcrAvailability.NO_PLAY_SERVICES

    override suspend fun recognize(frame: OcrFrame?): OcrResult {
        val tess = engine() ?: return OcrResult.Failed("tesseract unavailable")
        val image = frame?.inputImage ?: return OcrResult.Failed("no camera frame")
        // bitmap-backed frames (tests, gallery imports) carry a bitmap directly;
        // CameraX frames carry a YUV media image that needs conversion
        val bitmap = image.bitmapInternal
            ?: image.mediaImage?.let { media ->
                withContext(Dispatchers.Default) { toBitmap(media, image.rotationDegrees) }
            }
            ?: return OcrResult.Failed("frame conversion failed")
        return apiMutex.withLock {
            try {
                tess.setImage(bitmap)
                val text = tess.utF8Text.orEmpty()
                val blocks = text.split(Regex("\n\\s*\n"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { OcrBlock(it) }
                if (blocks.isEmpty()) OcrResult.Failed("no text found")
                else OcrResult.Blocks(blocks)
            } catch (e: Exception) {
                Log.w(TAG, "recognition failed", e)
                OcrResult.Failed(e.message ?: "recognition failed")
            }
        }
    }

    private suspend fun engine(): TessBaseAPI? {
        api?.let { return it }
        if (initFailed) return null
        return apiMutex.withLock {
            api?.let { return@withLock it }
            if (initFailed) return@withLock null
            val created = withContext(Dispatchers.IO) {
                runCatching {
                    installTraineddata()
                    TessBaseAPI().apply {
                        val ok = init(context.filesDir.absolutePath, LANG)
                        if (!ok) error("TessBaseAPI.init returned false")
                    }
                }.onFailure { Log.e(TAG, "tesseract init failed", it) }.getOrNull()
            }
            if (created == null) initFailed = true
            api = created
            created
        }
    }

    /** Copy assets/tessdata into filesDir/tessdata (idempotent, size-checked). */
    private fun installTraineddata() {
        val destDir = File(context.filesDir, "tessdata")
        destDir.mkdirs()
        context.assets.list("tessdata")?.forEach { name ->
            val dest = File(destDir, name)
            context.assets.open("tessdata/$name").use { input ->
                if (!dest.exists() || dest.length() != input.available().toLong()) {
                    dest.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    private fun toBitmap(image: Image, rotationDegrees: Int): Bitmap? = runCatching {
        val nv21 = yuv420ToNv21(image)
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
        val raw = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size()) ?: return null
        if (rotationDegrees == 0) raw
        else Bitmap.createBitmap(
            raw, 0, 0, raw.width, raw.height,
            Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true,
        )
    }.onFailure { Log.w(TAG, "frame conversion failed", it) }.getOrNull()

    /** YUV_420_888 → NV21, honoring row/pixel strides (CameraX output). */
    private fun yuv420ToNv21(image: Image): ByteArray {
        val w = image.width
        val h = image.height
        val ySize = w * h
        val uvSize = w * h / 2
        val out = ByteArray(ySize + uvSize)

        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride
        var pos = 0
        if (yPixStride == 1) {
            for (row in 0 until h) {
                yBuf.position(row * yRowStride)
                yBuf.get(out, pos, w)
                pos += w
            }
        } else {
            for (row in 0 until h) {
                var p = row * yRowStride
                for (col in 0 until w) {
                    out[pos++] = yBuf.get(p)
                    p += yPixStride
                }
            }
        }

        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uvH = h / 2
        val uvW = w / 2
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uRow = uPlane.rowStride
        val vRow = vPlane.rowStride
        val uPix = uPlane.pixelStride
        val vPix = vPlane.pixelStride
        for (row in 0 until uvH) {
            var up = row * uRow
            var vp = row * vRow
            for (col in 0 until uvW) {
                out[pos++] = vBuf.get(vp) // NV21: V first
                out[pos++] = uBuf.get(up)
                up += uPix
                vp += vPix
            }
        }
        return out
    }

    fun release() {
        runCatching { api?.recycle() }
        api = null
        initFailed = false
    }

    companion object {
        private const val TAG = "TessOcrGateway"
        private const val LANG = "ita"
    }
}
