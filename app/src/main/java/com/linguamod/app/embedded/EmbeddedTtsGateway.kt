package com.linguamod.app.embedded

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.linguamod.app.audio.TtsGateway
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Stage 9: embedded open-source Italian voice — sherpa-onnx (Apache-2.0)
 * running a Piper VITS model fully on-device. No system TTS engine, no
 * network, no telemetry. Used by [FallbackTtsGateway] when the device has no
 * Italian system voice (GMS-free phones, bare AOSP).
 *
 * The model pack ([ModelManager.TTS_PACK]) is a one-time ~21 MB download from
 * the project's own GitHub release; after that synthesis is offline forever.
 */
class EmbeddedTtsGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: ModelManager,
) : TtsGateway {

    private val initMutex = Mutex()

    @Volatile
    private var tts: OfflineTts? = null

    @Volatile
    private var initFailed = false

    private val opMutex = Mutex()

    override suspend fun isItalianVoiceAvailable(): Boolean = models.isReady(ModelManager.TTS_PACK.id)

    override suspend fun speak(text: String, speechRate: Float): Boolean {
        val audio = synthesize(text, speechRate) ?: return false
        return playPcm(audio.samples, audio.sampleRate)
    }

    override suspend fun synthesizeToFile(text: String, outFile: File): Boolean {
        val audio = synthesize(text, 1.0f) ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                writeWav(outFile, audio.samples, audio.sampleRate)
                outFile.exists() && outFile.length() > 44
            }.getOrDefault(false)
        }
    }

    override fun release() {
        opMutex.tryLock() // best effort: serialize with in-flight ops
        runCatching { tts?.release() }
        tts = null
        initFailed = false
        opMutex.unlock()
    }

    private suspend fun synthesize(text: String, speed: Float): Audio? {
        val engine = engine() ?: return null
        return opMutex.withLock {
            withContext(Dispatchers.Default) {
                runCatching {
                    val generated = engine.generate(text, sid = 0, speed = speed)
                    if (generated.samples.isEmpty()) null
                    else Audio(generated.samples, generated.sampleRate)
                }.onFailure { Log.w(TAG, "synthesis failed", it) }.getOrNull()
            }
        }
    }

    private suspend fun engine(): OfflineTts? {
        tts?.let { return it }
        if (initFailed) return null
        if (!models.isReady(ModelManager.TTS_PACK.id)) return null
        return initMutex.withLock {
            tts?.let { return@withLock it }
            if (initFailed) return@withLock null
            val created = withContext(Dispatchers.Default) {
                runCatching {
                    val dir = models.packDir(ModelManager.TTS_PACK.id)
                    val modelFile = dir.listFiles { f -> f.extension == "onnx" }?.firstOrNull()
                        ?: error("no .onnx in ${dir.absolutePath}")
                    val tokens = File(dir, "tokens.txt")
                    val espeakDir = File(dir, "espeak-ng-data")
                    val config = OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            vits = OfflineTtsVitsModelConfig(
                                model = modelFile.absolutePath,
                                tokens = tokens.absolutePath,
                                dataDir = espeakDir.absolutePath,
                            ),
                            numThreads = 2,
                            debug = false,
                            provider = "cpu",
                        ),
                    )
                    OfflineTts(assetManager = null, config = config)
                }.onFailure { Log.e(TAG, "OfflineTts init failed", it) }.getOrNull()
            }
            if (created == null) initFailed = true
            tts = created
            created
        }
    }

    /** Blocking PCM playback; true when the clip finished (or was written fully). */
    private suspend fun playPcm(samples: FloatArray, sampleRate: Int): Boolean =
        withContext(Dispatchers.Default) {
            val shorts = ShortArray(samples.size) { i ->
                (samples[i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
            }
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, shorts.size * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            try {
                track.play()
                var off = 0
                while (off < shorts.size && isActive) {
                    off += track.write(shorts, off, shorts.size - off)
                }
                // wait until the written frames actually played out
                val deadline = System.currentTimeMillis() +
                    (shorts.size * 1000L / sampleRate) + 2_000
                while (track.playbackHeadPosition < shorts.size &&
                    System.currentTimeMillis() < deadline && isActive
                ) {
                    delay(40)
                }
                true
            } catch (e: Exception) {
                Log.w(TAG, "playback failed", e)
                false
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }

    /** Minimal PCM16 WAV writer (monophonic). */
    private fun writeWav(out: File, samples: FloatArray, sampleRate: Int) {
        val dataSize = samples.size * 2
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            // RIFF header (sizes patched after data)
            raf.writeBytes("RIFF")
            raf.writeIntLE(36 + dataSize)
            raf.writeBytes("WAVEfmt ")
            raf.writeIntLE(16)
            raf.writeShortLE(1) // PCM
            raf.writeShortLE(1) // mono
            raf.writeIntLE(sampleRate)
            raf.writeIntLE(sampleRate * 2)
            raf.writeShortLE(2) // block align
            raf.writeShortLE(16) // bits
            raf.writeBytes("data")
            raf.writeIntLE(dataSize)
            val buf = ByteArray(dataSize)
            for (i in samples.indices) {
                val v = (samples[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
                buf[i * 2] = (v and 0xff).toByte()
                buf[i * 2 + 1] = (v shr 8 and 0xff).toByte()
            }
            raf.write(buf)
        }
    }

    private fun RandomAccessFile.writeIntLE(v: Int) = write(
        byteArrayOf(
            (v and 0xff).toByte(), (v shr 8 and 0xff).toByte(),
            (v shr 16 and 0xff).toByte(), (v shr 24 and 0xff).toByte(),
        )
    )

    private fun RandomAccessFile.writeShortLE(v: Int) = write(
        byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte())
    )

    private class Audio(val samples: FloatArray, val sampleRate: Int)

    companion object { private const val TAG = "EmbeddedTtsGateway" }
}
