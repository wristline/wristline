package dev.wristline.watch.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * Colors whose meaning is fixed on every screen, whatever the theme's accent: a session's state
 * (green running, yellow waiting on the user, gray idle, dark gray ended), and green for Allow.
 */
object Status {
    val Running = Color(0xFF30D158)
    val Attention = Color(0xFFFFD60A)
    val Idle = Color(0xFF8E8E93)
    val Ended = Color(0xFF48484A)
}

/**
 * Pure black with neutral graphite surfaces, white and gray text, and the brand's sky blue as the
 * one accent. Every role is set: one left out would fall back to the library's purple baseline.
 * Gray text (#98989F) is 7.3:1 on the black and 5.8:1 on a card.
 */
val WristlineColors = ColorScheme(
    primary = Color(0xFF4FA8FF),
    // The brand accent (branding/README.md), a step under the primary.
    primaryDim = Color(0xFF2F8FE8),
    // The user's own messages: navy under light blue text.
    primaryContainer = Color(0xFF0E2A47),
    onPrimary = Color.Black,
    onPrimaryContainer = Color(0xFFD6E9FF),
    // Used by none of the app's components; neutral so nothing brings in a second accent.
    secondary = Color(0xFFD1D1D6),
    secondaryDim = Color(0xFFAEAEB2),
    secondaryContainer = Color(0xFF3A3A3C),
    onSecondary = Color.Black,
    onSecondaryContainer = Color.White,
    // "Look here": the request banner and [Respond].
    tertiary = Status.Attention,
    tertiaryDim = Color(0xFFD6B300),
    tertiaryContainer = Color(0xFF3D3300),
    onTertiary = Color.Black,
    onTertiaryContainer = Color(0xFFFFF1A8),
    // Tool calls sit on the low surface, under the messages' cards.
    surfaceContainerLow = Color(0xFF121214),
    surfaceContainer = Color(0xFF1E1E20),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFF98989F),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFF48484A),
    background = Color.Black,
    onBackground = Color.White,
    error = Color(0xFFFF453A),
    errorDim = Color(0xFFE0352B),
    errorContainer = Color(0xFF5C1A16),
    onError = Color.Black,
    onErrorContainer = Color(0xFFFFDAD6),
)

/** The app's theme: [WristlineColors] with the library's type, shapes and motion. */
@Composable
fun WristlineTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = WristlineColors, content = content)
}
