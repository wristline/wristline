package dev.wristline.watch.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.wristline.watch.data.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeColorsTest {
    @Test
    fun limitIsWhiteUnderEighty() {
        assertEquals(Color.White, limitColor(0))
        assertEquals(Color.White, limitColor(79))
    }

    @Test
    fun limitIsYellowFromEightyUnderNinetyFive() {
        assertEquals(Status.Attention, limitColor(80))
        assertEquals(Status.Attention, limitColor(94))
    }

    @Test
    fun limitIsRedFromNinetyFive() {
        assertEquals(WristlineColors.error, limitColor(95))
        assertEquals(WristlineColors.error, limitColor(100))
        // Past the limit (a window can report over 100) stays red.
        assertEquals(WristlineColors.error, limitColor(130))
    }

    @Test
    fun statusColorsAreFixed() {
        assertEquals(Status.Running, statusColor(SessionStatus.RUNNING))
        assertEquals(Status.Attention, statusColor(SessionStatus.NEEDS_INPUT))
        assertEquals(Status.Idle, statusColor(SessionStatus.IDLE))
        assertEquals(Status.Ended, statusColor(SessionStatus.ENDED))
        // A status this version does not know reads as ended, as its description does.
        assertEquals(Status.Ended, statusColor("paused"))
    }

    @Test
    fun endedDotStaysVisibleOnACardYetUnderIdle() {
        fun contrast(a: Color, b: Color) = (maxOf(a.luminance(), b.luminance()) + 0.05f) / (minOf(a.luminance(), b.luminance()) + 0.05f)
        assertTrue(contrast(Status.Ended, WristlineColors.surfaceContainer) > 2.5f)
        assertTrue(Status.Ended.luminance() < Status.Idle.luminance())
    }

    @Test
    fun watchColorsOnlyWhenChosenAndOffered() {
        val watch = WristlineColors.copy(primary = Color(0xFF7FD17F))
        assertEquals(WristlineColors, themeColors(followWatch = false) { watch })
        assertEquals(watch, themeColors(followWatch = true) { watch })
        // The watch has none to offer (older Wear OS, or dynamic theming off).
        assertEquals(WristlineColors, themeColors(followWatch = true) { null })
    }

    @Test
    fun theWatchColorsAreNotAskedForWhenOff() {
        themeColors(followWatch = false) { error("read the watch colors") }
    }
}
