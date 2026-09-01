package com.linguamod.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AccentGreen = Color(0xFF2E7D32)

private val LightColors = lightColorScheme(
    primary = AccentGreen,
    secondary = Color(0xFF00695C),
    tertiary = Color(0xFF8D6E63),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF81C784),
    secondary = Color(0xFF4DB6AC),
    tertiary = Color(0xFFBCAAA4),
)

/** Dark mode follows the system; accent color may be overridden by a purchased theme. */
@Composable
fun LinguaModTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: Color? = null,
    altDark: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        darkTheme && altDark -> darkColorScheme(
            primary = accent ?: Color(0xFF81C784),
            background = Color(0xFF10151A),
            surface = Color(0xFF1A2128),
        )
        darkTheme -> DarkColors.let { c -> if (accent != null) c.copy(primary = accent) else c }
        else -> LightColors.let { c -> if (accent != null) c.copy(primary = accent) else c }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
