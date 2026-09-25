package com.linguamod.app.audio

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.util.Log
import com.linguamod.app.data.AudioStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Lets composables reach the [TtsManager] singleton without constructor plumbing. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AudioEntryPoint {
    fun ttsManager(): TtsManager
}

fun Context.ttsManager(): TtsManager =
    EntryPointAccessors.fromApplication(applicationContext, AudioEntryPoint::class.java).ttsManager()

/**
 * The app's voice (Stage 3 §1). Central TTS facade, Hilt singleton, Italian.
 *
 * - When the Italian voice is missing, [audioAvailable] goes false: every audio
 *   button hides (never errors/crashes/blocks) and a one-time
 *   "Install the Italian voice" prompt is raised ([showVoiceInstallPrompt]).
 * - Playback is cached: synthesizeToFile into `cacheDir/tts/<sha1>.wav` keyed by
 *   normalized text (cap 500, LRU — see [TtsFileCache]), replayed via MediaPlayer;
 *   cache is checked before any synthesis. On any failure falls back to direct
 *   [TtsGateway.speak].
 */
@Singleton
class TtsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gateway: TtsGateway,
    private val audioStore: AudioStore,
    private val models: com.linguamod.app.embedded.ModelManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _audioAvailable = MutableStateFlow(false)
    val audioAvailable: StateFlow<Boolean> = _audioAvailable

    private val _showVoiceInstallPrompt = MutableStateFlow(false)
    val showVoiceInstallPrompt: StateFlow<Boolean> = _showVoiceInstallPrompt

    /** False on GMS-free devices with no system TTS engine at all (Chinese
     *  ROMs): the "open system TTS settings" escape hatch is a dead end there,
     *  so the UI hides it and relies solely on the embedded voice download. */
    private val _systemTtsEnginePresent = MutableStateFlow(true)
    val systemTtsEnginePresent: StateFlow<Boolean> = _systemTtsEnginePresent

    private val cache = TtsFileCache(File(context.cacheDir, "tts"))

    @Volatile
    private var player: MediaPlayer? = null

    init {
        scope.launch {
            _systemTtsEnginePresent.value = hasSystemTtsEngine()
            val available = runCatching { gateway.isItalianVoiceAvailable() }.getOrDefault(false)
            _audioAvailable.value = available
            // Stage 9 bootstrap only runs against the REAL fallback gateway —
            // instrumented tests swap in fakes and must never hit the network.
            val realAudio = gateway is com.linguamod.app.embedded.FallbackTtsGateway
            if (!available) {
                if (!audioStore.wasVoicePromptShown()) {
                    _showVoiceInstallPrompt.value = true
                }
                // no usable voice anywhere → fetch the embedded open-source
                // voice automatically (small, one-time, private)
                if (realAudio) startVoicePackDownload()
            }
            if (realAudio) bootstrapSpeechPack()
        }
    }

    /** Embedded-voice pack state for the install dialog / progress UI. */
    val ttsPackState = models.states

    /** Downloads the embedded Italian voice pack (idempotent); on success
     *  audio becomes available and the UI un-hides audio buttons. */
    fun startVoicePackDownload() {
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                models.ensureInstalled(com.linguamod.app.embedded.ModelManager.TTS_PACK)
            }.getOrDefault(false)
            if (ok && runCatching { gateway.isItalianVoiceAvailable() }.getOrDefault(false)) {
                _audioAvailable.value = true
            }
        }
    }

    /** Speaking exercises need a recognizer; on GMS-free devices fetch the
     *  embedded Whisper pack. ~116 MB — auto only on unmetered networks;
     *  metered connections get it lazily when the user first opens a
     *  speaking exercise (they asked for it). */
    private fun bootstrapSpeechPack() {
        scope.launch(Dispatchers.IO) {
            val systemHasRecognizer = runCatching {
                android.speech.SpeechRecognizer.isRecognitionAvailable(context)
            }.getOrDefault(false)
            if (systemHasRecognizer) return@launch
            if (models.isReady(com.linguamod.app.embedded.ModelManager.STT_PACK.id)) return@launch
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return@launch
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@launch
            val unmetered = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            if (unmetered) {
                runCatching { models.ensureInstalled(com.linguamod.app.embedded.ModelManager.STT_PACK) }
            }
        }
    }

    /** True when at least one system TTS engine service is installed. */
    private fun hasSystemTtsEngine(): Boolean = runCatching {
        context.packageManager
            .queryIntentServices(Intent("android.intent.action.TTS_SERVICE"), 0)
            .isNotEmpty()
    }.getOrDefault(false)

    /** Deep-link to the system TTS settings so the user can install Italian. */
    fun openTtsSettings() {
        runCatching {
            context.startActivity(
                Intent("com.android.settings.TTS_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Persist that the one-time voice-install dialog was displayed. */
    suspend fun markVoicePromptShown() {
        audioStore.markVoicePromptShown()
        _showVoiceInstallPrompt.value = false
    }

    /**
     * Plays [text] in Italian at [rate] (1.0 normal, ~0.75 slow replay).
     * Never throws; no-ops when audio is unavailable.
     */
    suspend fun play(text: String, rate: Float = 1.0f) {
        if (text.isBlank()) return
        // Ask the gateway directly, not the flow: the flow starts false until the
        // async init lands, and a listening exercise may autoplay before that.
        if (!runCatching { gateway.isItalianVoiceAvailable() }.getOrDefault(false)) return
        // cache first: replay without re-synthesis
        cache.get(text)?.let { cached ->
            if (playFile(cached, rate)) return
        }
        val target = cache.fileFor(text)
        val synthesized = runCatching { gateway.synthesizeToFile(text, target) }
            .getOrDefault(false) && target.exists() && target.length() > 0
        if (synthesized) {
            cache.commit(text)
            if (playFile(target, rate)) return
        }
        // fallback: speak directly (no cache), still never an error path
        runCatching { gateway.speak(text, rate) }
    }

    /** MediaPlayer replay; false on any playback error. */
    private suspend fun playFile(file: File, rate: Float): Boolean =
        suspendCancellableCoroutine { cont ->
            val mp = MediaPlayer()
            try {
                synchronized(this) {
                    player?.let { runCatching { it.release() } }
                    player = mp
                }
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener {
                    if (cont.isActive) cont.resume(true)
                    runCatching { it.release() }
                    synchronized(this) { if (player === mp) player = null }
                }
                mp.setOnErrorListener { _, _, _ ->
                    if (cont.isActive) cont.resume(false)
                    runCatching { mp.release() }
                    synchronized(this) { if (player === mp) player = null }
                    true
                }
                mp.prepare()
                mp.playbackParams = mp.playbackParams.setSpeed(rate)
                mp.start()
            } catch (t: Throwable) {
                Log.w(TAG, "cached replay failed for ${file.name}", t)
                runCatching { mp.release() }
                synchronized(this) { if (player === mp) player = null }
                if (cont.isActive) cont.resume(false)
            }
        }

    companion object {
        private const val TAG = "TtsManager"
    }
}
