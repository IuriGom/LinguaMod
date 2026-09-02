package com.linguamod.app.fakes

import com.linguamod.app.audio.RecognitionOutcome
import com.linguamod.app.audio.SpeechRecognizerGateway
import com.linguamod.app.audio.TtsGateway
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Singleton

/**
 * Fake TTS (Stage 3 §0): records every "spoken" string (with the requested
 * rate) and every synthesis, and can be configured unavailable. Synthesis
 * writes a real (silent) WAV so MediaPlayer replay works against the fake.
 */
class FakeTtsGateway : TtsGateway {
    @Volatile
    var available: Boolean = true

    @Volatile
    var synthSucceeds: Boolean = true

    /** (text, rate) pairs passed to speak(). */
    val spoken = CopyOnWriteArrayList<Pair<String, Float>>()

    /** Texts passed to synthesizeToFile(). */
    val synthesized = CopyOnWriteArrayList<String>()

    override suspend fun isItalianVoiceAvailable(): Boolean = available

    override suspend fun speak(text: String, speechRate: Float): Boolean {
        if (!available) return false
        spoken += text to speechRate
        return true
    }

    override suspend fun synthesizeToFile(text: String, outFile: File): Boolean {
        if (!available || !synthSucceeds) return false
        synthesized += text
        outFile.parentFile?.mkdirs()
        outFile.writeBytes(SILENT_WAV)
        return true
    }

    override fun release() {}

    companion object {
        /** A valid 0.1 s 16-bit mono 22050 Hz PCM WAV of silence. */
        val SILENT_WAV: ByteArray = run {
            val samples = 2205
            val dataSize = samples * 2
            val header = ByteArray(44)
            fun putAscii(off: Int, s: String) = s.toByteArray(Charsets.US_ASCII)
                .copyInto(header, off)
            fun putInt(off: Int, v: Int) {
                header[off] = v.toByte()
                header[off + 1] = (v shr 8).toByte()
                header[off + 2] = (v shr 16).toByte()
                header[off + 3] = (v shr 24).toByte()
            }
            fun putShort(off: Int, v: Int) {
                header[off] = v.toByte()
                header[off + 1] = (v shr 8).toByte()
            }
            putAscii(0, "RIFF"); putInt(4, 36 + dataSize); putAscii(8, "WAVE")
            putAscii(12, "fmt "); putInt(16, 16); putShort(20, 1); putShort(22, 1)
            putInt(24, 22050); putInt(28, 22050 * 2); putShort(32, 2); putShort(34, 16)
            putAscii(36, "data"); putInt(40, dataSize)
            header + ByteArray(dataSize)
        }
    }
}

/**
 * Fake recognizer (Stage 3 §0): scripts transcripts and simulates recognition
 * unavailable, permission denied, partial results, and arbitrary heard text.
 * With no [script] set it echoes the expected phrase (a perfect learner).
 */
class FakeSpeechRecognizerGateway : SpeechRecognizerGateway {
    @Volatile
    var available: Boolean = true

    @Volatile
    var permissionGranted: Boolean = true

    /** When set, [listen] returns this transcript instead of echoing the target. */
    @Volatile
    var script: String? = null

    /** When set, [listen] fails with this reason (after availability checks). */
    @Volatile
    var failWith: String? = null

    @Volatile
    var emitPartials: Boolean = true

    /** Every expected phrase passed to listen(). */
    val listenCalls = CopyOnWriteArrayList<String>()

    override fun isRecognitionAvailable(): Boolean = available

    override fun isMicPermissionGranted(): Boolean = permissionGranted

    override suspend fun listen(expectedPhrase: String): RecognitionOutcome {
        listenCalls += expectedPhrase
        if (!available) return RecognitionOutcome.Unavailable
        if (!permissionGranted) return RecognitionOutcome.PermissionDenied
        failWith?.let { return RecognitionOutcome.Failed(it) }
        val transcript = script ?: expectedPhrase
        val partials = if (emitPartials) {
            transcript.split(" ").filter { it.isNotBlank() }.dropLast(1)
        } else {
            emptyList()
        }
        return RecognitionOutcome.Heard(transcript, partials)
    }
}

/**
 * Provides the fake audio gateways for every instrumented test: all journeys
 * uninstall `AppModule` (which binds the real gateways), and Hilt picks this
 * module up for the whole androidTest component.
 */
@Module
@InstallIn(SingletonComponent::class)
object TestAudioModule {
    @Provides
    @Singleton
    fun provideFakeTts(): FakeTtsGateway = FakeTtsGateway()

    @Provides
    @Singleton
    fun provideTtsGateway(fake: FakeTtsGateway): TtsGateway = fake

    @Provides
    @Singleton
    fun provideFakeRecognizer(): FakeSpeechRecognizerGateway = FakeSpeechRecognizerGateway()

    @Provides
    @Singleton
    fun provideSpeechRecognizerGateway(fake: FakeSpeechRecognizerGateway): SpeechRecognizerGateway = fake
}

/**
 * Fake OCR gateway (Stage 4 §1): scripts recognized text blocks and simulates
 * "no Play Services" and "model still downloading". Ignores camera frames, so
 * the whole recognized-blocks → word-tap flow is testable headless.
 */
class FakeOcrGateway : com.linguamod.app.ocr.OcrGateway {
    @Volatile
    var available: Boolean = true

    @Volatile
    var modelReady: Boolean = true

    /** Scripted recognized block texts returned by recognize(). */
    @Volatile
    var script: List<String> = emptyList()

    @Volatile
    var failWith: String? = null

    /** Every recognize() call (true = a real camera frame was handed over). */
    val recognizeCalls = CopyOnWriteArrayList<Boolean>()

    override suspend fun availability(): com.linguamod.app.ocr.OcrAvailability =
        if (available) com.linguamod.app.ocr.OcrAvailability.READY
        else com.linguamod.app.ocr.OcrAvailability.NO_PLAY_SERVICES

    override suspend fun recognize(frame: com.linguamod.app.ocr.OcrFrame?): com.linguamod.app.ocr.OcrResult {
        recognizeCalls += (frame != null)
        failWith?.let { return com.linguamod.app.ocr.OcrResult.Failed(it) }
        if (!modelReady) return com.linguamod.app.ocr.OcrResult.ModelDownloading
        return com.linguamod.app.ocr.OcrResult.Blocks(script.map { com.linguamod.app.ocr.OcrBlock(it) })
    }
}

/** Provides the fake OCR gateway for every instrumented test (see TestAudioModule). */
@Module
@InstallIn(SingletonComponent::class)
object TestOcrModule {
    @Provides
    @Singleton
    fun provideFakeOcr(): FakeOcrGateway = FakeOcrGateway()

    @Provides
    @Singleton
    fun provideOcrGateway(fake: FakeOcrGateway): com.linguamod.app.ocr.OcrGateway = fake
}
