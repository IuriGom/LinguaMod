package com.linguamod.app.embedded

import android.content.Context
import com.linguamod.app.audio.TtsGateway
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * Stage 9: TTS with backup-for-the-backup. The system engine stays primary
 * (best OEM voices when present); the embedded open-source Piper voice takes
 * over when the device has no Italian system voice (GMS-free phones).
 * Consumers see one [TtsGateway]; availability flips on as soon as either
 * engine can speak Italian.
 */
class FallbackTtsGateway @Inject constructor(
    @ApplicationContext context: Context,
    private val models: ModelManager,
) : TtsGateway {

    private val system = com.linguamod.app.audio.AndroidTtsGateway(context)
    private val embedded = EmbeddedTtsGateway(context, models)

    override suspend fun isItalianVoiceAvailable(): Boolean =
        system.isItalianVoiceAvailable() || embedded.isItalianVoiceAvailable()

    override suspend fun speak(text: String, speechRate: Float): Boolean =
        when {
            system.isItalianVoiceAvailable() -> system.speak(text, speechRate)
            embedded.isItalianVoiceAvailable() -> embedded.speak(text, speechRate)
            else -> false
        }

    override suspend fun synthesizeToFile(text: String, outFile: File): Boolean =
        when {
            system.isItalianVoiceAvailable() -> system.synthesizeToFile(text, outFile)
            embedded.isItalianVoiceAvailable() -> embedded.synthesizeToFile(text, outFile)
            else -> false
        }

    override fun release() {
        system.release()
        embedded.release()
    }
}
