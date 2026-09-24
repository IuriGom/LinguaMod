package com.linguamod.app.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 9: proves the bundled open-source OCR engine works on-device with no
 * Play Services involvement — the Italian traineddata ships in assets and the
 * whole pipeline (init → bitmap → text) runs offline.
 */
@RunWith(AndroidJUnit4::class)
class TessOcrGatewayTest {

    private val gateway = TessOcrGateway(ApplicationProvider.getApplicationContext())

    @After
    fun tearDown() = gateway.release()

    private fun textBitmap(text: String): Bitmap {
        val bmp = Bitmap.createBitmap(800, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 64f
        }
        canvas.drawText(text, 40f, 120f, paint)
        return bmp
    }

    @Test
    fun availability_is_ready_without_play_services() = runBlocking {
        assertEquals(OcrAvailability.READY, gateway.availability())
    }

    @Test
    fun recognizes_printed_italian_text_offline() = runBlocking {
        val frame = OcrFrame(InputImage.fromBitmap(textBitmap("ciao mondo"), 0))
        val result = gateway.recognize(frame)
        assertTrue("expected Blocks, got $result", result is OcrResult.Blocks)
        val text = (result as OcrResult.Blocks).blocks.joinToString(" ") { it.text }.lowercase()
        assertTrue("recognized text must contain 'ciao', got: $text", "ciao" in text)
    }

    @Test
    fun empty_frame_fails_cleanly() = runBlocking {
        val result = gateway.recognize(null)
        assertTrue(result is OcrResult.Failed)
    }
}
