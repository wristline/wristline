package dev.wristline.watch.ui

import dev.wristline.watch.data.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Test

class LimitTimeTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun left(millis: Long, days: String = "%1\$dd") = remainingText(minutesLeft(now + millis, now), days)

    @Test
    fun underADayIsHoursAndMinutes() {
        assertEquals("0:05", left(5 * minute))
        assertEquals("2:13", left((2 * 60 + 13) * minute))
        assertEquals("23:59", left(24 * 60 * minute - 1))
        // Whole minutes only: 59 seconds left reads 0:00.
        assertEquals("0:00", left(59_000))
    }

    @Test
    fun passedOrNowIsZero() {
        assertEquals("0:00", left(0))
        assertEquals("0:00", left(-3 * 60 * minute))
    }

    @Test
    fun fromADayIsWholeDays() {
        assertEquals("1d", left(24 * 60 * minute))
        assertEquals("1일", left(47 * 60 * minute, "%1\$d일"))
        assertEquals("3d", left((3 * 24 * 60 + 192) * minute))
        assertEquals("30d", left(30 * 24 * 60 * minute + 1))
        assertEquals("30일", left(30 * 24 * 60 * minute, "%1\$d일"))
    }

    @Test
    fun aPassedResetEndsTheWindow() {
        val line = LimitLine(ProviderId.CLAUDE_CODE, 100, now + minute, 2)
        assertEquals(line, currentLine(line, now))
        // Under a minute left the window still shows, at 0:00.
        assertEquals(line, currentLine(line, now + 59_000))
        // From its reset time: as without usage, the session count kept.
        assertEquals(LimitLine(ProviderId.CLAUDE_CODE, null, null, 2), currentLine(line, now + minute))
        // Without a reset time nothing ends it.
        val open = line.copy(resetsAt = null)
        assertEquals(open, currentLine(open, now + 365L * 24 * 60 * minute))
    }
}
