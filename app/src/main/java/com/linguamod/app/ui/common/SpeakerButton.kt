package com.linguamod.app.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.linguamod.app.audio.ttsManager
import kotlinx.coroutines.launch

/**
 * Speaker icon (Stage 3 §1). Renders nothing when the Italian voice is missing —
 * audio buttons hide rather than error. Tap plays [text] at [rate]; no autoplay
 * here (listening exercises drive playback themselves).
 */
@Composable
fun SpeakerButton(
    text: String,
    rate: Float = 1.0f,
    tag: String = "speaker_button",
) {
    val tts = LocalContext.current.ttsManager()
    val available by tts.audioAvailable.collectAsState()
    if (!available || text.isBlank()) return
    val scope = rememberCoroutineScope()
    IconButton(
        onClick = { scope.launch { tts.play(text, rate) } },
        modifier = Modifier.testTag(tag),
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = "Play audio",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}
