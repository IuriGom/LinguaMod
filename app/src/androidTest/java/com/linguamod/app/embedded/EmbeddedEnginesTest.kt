package com.linguamod.app.embedded

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 9 acceptance: the embedded open-source engines work end-to-end with
 * zero external setup — the model packs download from the project's own
 * GitHub release (checksum-verified), install into internal storage, and
 * Piper synthesizes Italian speech fully offline afterwards.
 *
 * Network-dependent by design (it exercises the real download path); the rest
 * of the suite stays hermetic via fakes.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddedEnginesTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val models = ModelManager(context)

    @Test
    fun tts_pack_downloads_installs_and_synthesizes_italian() = runBlocking {
        assertTrue("TTS pack install failed", models.ensureInstalled(ModelManager.TTS_PACK))
        val gateway = EmbeddedTtsGateway(context, models)
        try {
            assertTrue(gateway.isItalianVoiceAvailable())
            val out = File(context.cacheDir, "embedded_tts_test.wav")
            assertTrue(
                "synthesis failed",
                gateway.synthesizeToFile("Ciao, come stai? Tutto bene.", out),
            )
            assertTrue("wav too small: ${out.length()}", out.length() > 1_000)
        } finally {
            gateway.release()
        }
    }

    @Test
    fun stt_pack_downloads_installs_and_engine_initializes() = runBlocking {
        assertTrue("STT pack install failed", models.ensureInstalled(ModelManager.STT_PACK))
        val gateway = EmbeddedSttGateway(context, models)
        try {
            assertTrue(gateway.isRecognitionAvailable())
        } finally {
            gateway.release()
        }
    }
}
