package dev.wristline.watch.ui

import androidx.compose.ui.graphics.Color

/**
 * The colors an account's mark can have: its disc and the glyph on it, each pair at least 4.5:1
 * (ThemeColorsTest). White by default; the others from the Okabe-Ito set, which stays apart for
 * color-blind eyes, without its yellow and vermilion, which would read as the 80% and 95% limit
 * colors. [id] is what Prefs stores (see [dev.wristline.watch.data.AccountStyle.color]).
 */
internal enum class MarkColor(val id: String?, val disc: Color, val glyph: Color) {
    WHITE(null, Color.White, Color.Black),
    SKY("sky", Color(0xFF56B4E9), Color.Black),
    GREEN("green", Color(0xFF009E73), Color.Black),
    ORANGE("orange", Color(0xFFE69F00), Color.Black),
    PURPLE("purple", Color(0xFFCC79A7), Color.Black),
    BLUE("blue", Color(0xFF0072B2), Color.White),
}

/** The palette entry of [id]; white for null or one this version does not know. */
internal fun markColor(id: String?): MarkColor = MarkColor.entries.firstOrNull { it.id == id } ?: MarkColor.WHITE
