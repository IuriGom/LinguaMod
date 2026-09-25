package com.linguamod.app.embedded

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.linguamod.app.audio.RecognitionOutcome
import com.linguamod.app.audio.SpeechRecognizerGateway
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Stage 9: embedded open-source Italian speech recognition — sherpa-onnx
 * (Apache-2.0) running multilingual Whisper fully on-device. No Google
 * recognizer, no network, audio never leaves the phone. Used by
 * [FallbackSttGateway] when the device has no system recognition service.
 *
 * The model pack ([ModelManager.STT_PACK]) is a one-time download from the
 * project's own GitHub release; recognition is offline forever after.
 */
class EmbeddedSttGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: ModelManager,
) : SpeechRecognizerGateway {

    private val initMutex = Mutex()

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    private var initFailed = false

    private val opMutex = Mutex()

    override fun isRecognitionAvailable(): Boolean = models.isReady(ModelManager.STT_PACK.id)

    override fun isMicPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun listen(expectedPhrase: String): RecognitionOutcome {
        if (!isRecognitionAvailable()) return RecognitionOutcome.Unavailable
        if (!isMicPermissionGranted()) return RecognitionOutcome.PermissionDenied
        val rec = engine() ?: return RecognitionOutcome.Unavailable
        return opMutex.withLock {
            val pcm = capture() ?: return@withLock RecognitionOutcome.Failed("microphone error")
            if (pcm.isEmpty()) return@withLock RecognitionOutcome.Failed("no speech detected")
            recognizePcmLocked(pcm)
        }
    }

    /** Decode 16 kHz mono PCM. Public test seam: the instrumented test feeds
     *  TTS-synthesized Italian speech here (TTS → STT closed loop, offline). */
    suspend fun recognizePcm(pcm: FloatArray): RecognitionOutcome {
        if (engine() == null) return RecognitionOutcome.Unavailable
        return opMutex.withLock { recognizePcmLocked(pcm) }
    }

    private suspend fun recognizePcmLocked(pcm: FloatArray): RecognitionOutcome {
        val rec = engine() ?: return RecognitionOutcome.Unavailable
        return withContext(Dispatchers.Default) {
                runCatching {
                    val stream = rec.createStream()
                    stream.acceptWaveform(pcm, SAMPLE_RATE)
                    rec.decode(stream)
                    val text = rec.getResult(stream).text.trim()
                    stream.release()
                    if (text.isBlank()) RecognitionOutcome.Failed("no match")
                    else RecognitionOutcome.Heard(text)
                }.getOrElse {
                    Log.w(TAG, "decode failed", it)
                    RecognitionOutcome.Failed(it.message ?: "decode failed")
                }
            }
    }

    fun release() {
        runCatching { recognizer?.release() }
        recognizer = null
        initFailed = false
    }

    private suspend fun engine(): OfflineRecognizer? {
        recognizer?.let { return it }
        if (initFailed) return null
        if (!models.isReady(ModelManager.STT_PACK.id)) return null
        return initMutex.withLock {
            recognizer?.let { return@withLock it }
            if (initFailed) return@withLock null
            val created = withContext(Dispatchers.Default) {
                runCatching {
                    val dir = models.packDir(ModelManager.STT_PACK.id)
                    val encoder = dir.walkTopDown().firstOrNull { it.name.contains("encoder") && it.extension == "onnx" }
                        ?: error("no encoder onnx in $dir")
                    val decoder = dir.walkTopDown().firstOrNull { it.name.contains("decoder") && it.extension == "onnx" }
                        ?: error("no decoder onnx in $dir")
                    val tokens = dir.walkTopDown().firstOrNull { it.name == "tiny-tokens.txt" || it.name == "tokens.txt" }
                        ?: error("no tokens file in $dir")
                    OfflineRecognizer(
                        assetManager = null,
                        config = OfflineRecognizerConfig(
                            modelConfig = OfflineModelConfig(
                                whisper = OfflineWhisperModelConfig(
                                    encoder = encoder.absolutePath,
                                    decoder = decoder.absolutePath,
                                    language = "it",
                                    task = "transcribe",
                                ),
                                tokens = tokens.absolutePath,
                                numThreads = 2,
                                debug = false,
                                provider = "cpu",
                            ),
                        ),
                    )
                }.onFailure { Log.e(TAG, "OfflineRecognizer init failed", it) }.getOrNull()
            }
            if (created == null) initFailed = true
            recognizer = created
            created
        }
    }

    /**
     * Records 16 kHz mono PCM until end-of-speech: waits up to [MAX_WAIT_MS]
     * for speech to start, then stops after [SILENCE_MS] of trailing silence
     * or [MAX_SPEECH_MS] total. Returns float samples, empty when nothing was
     * said, null on mic failure.
     */
    private suspend fun capture(): FloatArray? = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE / 2),
            )
        }.getOrElse {
            Log.w(TAG, "AudioRecord init failed", it)
            return@withContext null
        }
        val all = ArrayList<Short>(SAMPLE_RATE * MAX_SPEECH_MS / 1000)
        val buf = ShortArray(1600) // 100 ms
        try {
            record.startRecording()
            var speechStarted = false
            var silenceMs = 0
            var waitedMs = 0
            while (isActive) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) break
                val chunkMs = n * 1000 / SAMPLE_RATE
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val rms = sqrt(sum / n)
                if (!speechStarted) {
                    waitedMs += chunkMs
                    if (rms > SPEECH_RMS) {
                        speechStarted = true
                        for (i in 0 until n) all.add(buf[i])
                    } else if (waitedMs > MAX_WAIT_MS) {
                        break
                    }
                } else {
                    for (i in 0 until n) all.add(buf[i])
                    silenceMs = if (rms < SILENCE_RMS) silenceMs + chunkMs else 0
                    if (silenceMs >= SILENCE_MS) break
                    if (all.size >= SAMPLE_RATE * MAX_SPEECH_MS / 1000) break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "capture failed", e)
            return@withContext null
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
        FloatArray(all.size) { i -> all[i] / 32768f }
    }

    companion object {
        private const val TAG = "EmbeddedSttGateway"
        private const val SAMPLE_RATE = 16_000
        private const val SPEECH_RMS = 300.0
        private const val SILENCE_RMS = 200.0
        private const val MAX_WAIT_MS = 8_000
        private const val SILENCE_MS = 1_200
        private const val MAX_SPEECH_MS = 12_000
    }
}
