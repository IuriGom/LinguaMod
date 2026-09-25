package com.linguamod.app.embedded

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 9 acceptance: the embedded open-source engines work end-to-end on a
 * device with NO Google anything — packs install, Piper synthesizes Italian
 * speech, Whisper transcribes it back (TTS → STT closed loop, fully offline).
 *
 * Hermetic by default: packs are pre-staged from /sdcard/linguamod_models/
 * (adb push before the run; shell identity via uiAutomation) so the emulator's
 * flaky NAT never gates the engine verification. If the staging dir is absent,
 * the real network path via ModelManager.ensureInstalled is exercised instead.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddedEnginesTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val models = ModelManager(context)

    private fun shell(cmd: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(cmd).use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().decodeToString()
            }

    @Before
    fun stagePacks() {
        for (pack in listOf(ModelManager.TTS_PACK, ModelManager.STT_PACK)) {
            if (models.isReady(pack.id)) continue
            val staged = "/sdcard/linguamod_models/${pack.id}"
            // wrap in sh -c: on API 26 executeShellCommand does NOT invoke a
            // shell, so builtins/compound commands crash system_server
            val listing = shell("sh -c 'ls $staged'")
            if (listing.contains(".onnx") || listing.contains("tokens")) {
                val dest = models.packDir(pack.id).absolutePath
                // shell runs as root on the emulator — hand the files to the
                // app uid and fix SELinux labels or the app can't read them
                shell(
                    "sh -c 'rm -rf $dest && mkdir -p $dest && cp -r $staged/. $dest/ && touch $dest/.ready && " +
                        "chown -R $(stat -c %u /data/data/com.linguamod.app):$(stat -c %g /data/data/com.linguamod.app) $dest && " +
                        "restorecon -R $dest'"
                )
                val check = shell("sh -c 'ls $dest/.ready'")
                android.util.Log.i("EmbeddedEnginesTest", "staging ${pack.id}: $check")
            }
        }
    }

    @Test
    fun tts_pack_installs_and_synthesizes_italian() = runBlocking {
        if (!models.isReady(ModelManager.TTS_PACK.id)) {
            assertTrue("TTS pack install failed", models.ensureInstalled(ModelManager.TTS_PACK))
        }
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
    fun stt_pack_installs_and_hears_its_own_tts() = runBlocking {
        // closed loop: synthesize Italian with the embedded voice, then let the
        // embedded recognizer transcribe it — no network, no Google, no mic
        if (!models.isReady(ModelManager.TTS_PACK.id)) {
            assertTrue("TTS pack install failed", models.ensureInstalled(ModelManager.TTS_PACK))
        }
        if (!models.isReady(ModelManager.STT_PACK.id)) {
            assertTrue("STT pack install failed", models.ensureInstalled(ModelManager.STT_PACK))
        }
        val tts = EmbeddedTtsGateway(context, models)
        val stt = EmbeddedSttGateway(context, models)
        try {
            val wav = File(context.cacheDir, "loop_test.wav")
            assertTrue(tts.synthesizeToFile("ciao", wav))
            val (pcm, rate) = readWav(wav)
            assertTrue("empty wav", pcm.isNotEmpty())
            val pcm16k = resampleLinear(pcm, rate, 16_000)
            val outcome = stt.recognizePcm(pcm16k)
            assertTrue(
                "expected Heard, got $outcome",
                outcome is com.linguamod.app.audio.RecognitionOutcome.Heard,
            )
            val heard = (outcome as com.linguamod.app.audio.RecognitionOutcome.Heard).transcript
            assertTrue("expected 'ciao' in transcript, got: $heard", "ciao" in heard.lowercase())
        } finally {
            tts.release()
            stt.release()
        }
    }

    /** PCM16 mono WAV → float samples + sample rate (44-byte canonical header). */
    private fun readWav(file: File): Pair<FloatArray, Int> {
        RandomAccessFile(file, "r").use { raf ->
            raf.skipBytes(22)
            val channels = raf.readShortLE()
            val rate = raf.readIntLE()
            raf.skipBytes(6)
            val bits = raf.readShortLE()
            raf.skipBytesUntilDataChunk()
            require(channels.toInt() == 1 && bits.toInt() == 16) { "unexpected wav format" }
            val dataSize = (raf.length() - raf.filePointer).toInt()
            val shorts = dataSize / 2
            val out = FloatArray(shorts)
            for (i in 0 until shorts) out[i] = raf.readShortLE() / 32768f
            return out to rate
        }
    }

    private fun RandomAccessFile.readShortLE(): Short {
        val b0 = read(); val b1 = read()
        return ((b1 shl 8) or b0).toShort()
    }

    private fun RandomAccessFile.readIntLE(): Int {
        val b0 = read(); val b1 = read(); val b2 = read(); val b3 = read()
        return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }

    /** Find the "data" chunk (skip optional chunks like LIST/fact). */
    private fun RandomAccessFile.skipBytesUntilDataChunk() {
        while (true) {
            val id = ByteArray(4).also { read(it) }.decodeToString()
            val size = readIntLE()
            if (id == "data") return
            skipBytes(size + (size % 2))
        }
    }

    private fun resampleLinear(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return input
        val outLen = (input.size.toLong() * toRate / fromRate).toInt()
        return FloatArray(outLen) { i ->
            val pos = i.toDouble() * fromRate / toRate
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
            val a = input[minOf(idx, input.size - 1)]
            val b = input[minOf(idx + 1, input.size - 1)]
            a + (b - a) * frac
        }
    }
}
