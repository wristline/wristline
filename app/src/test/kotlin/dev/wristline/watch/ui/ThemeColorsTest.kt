package dev.wristline.watch.ui

import androidx.compose.ui.graphics.Color
import dev.wristline.watch.data.SessionStatus
import org.junit.Assert.assertEquals
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
    fun waitingOnTheUserIsTheThemesAttentionColor() {
        // The request banner and [Respond] use tertiary: the same yellow as a waiting session's dot.
        assertEquals(Status.Attention, WristlineColors.tertiary)
    }
}
