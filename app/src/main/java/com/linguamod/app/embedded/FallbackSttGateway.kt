package com.linguamod.app.embedded

import android.content.Context
import com.linguamod.app.audio.RecognitionOutcome
import com.linguamod.app.audio.SpeechRecognizerGateway
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Stage 9: speech recognition with backup-for-the-backup. The system
 * recognizer (Google, where present) stays primary; the embedded open-source
 * Whisper engine takes over on devices with no recognition service
 * (GMS-free phones). Consumers see one [SpeechRecognizerGateway].
 */
class FallbackSttGateway @Inject constructor(
    @ApplicationContext context: Context,
    private val models: ModelManager,
) : SpeechRecognizerGateway {

    private val system = com.linguamod.app.audio.AndroidSpeechRecognizerGateway(context)
    private val embedded = EmbeddedSttGateway(context, models)

    override fun isRecognitionAvailable(): Boolean =
        system.isRecognitionAvailable() || embedded.isRecognitionAvailable()

    override fun isMicPermissionGranted(): Boolean =
        system.isMicPermissionGranted()

    override suspend fun listen(expectedPhrase: String): RecognitionOutcome =
        when {
            system.isRecognitionAvailable() -> system.listen(expectedPhrase)
            embedded.isRecognitionAvailable() -> embedded.listen(expectedPhrase)
            else -> RecognitionOutcome.Unavailable
        }
}
