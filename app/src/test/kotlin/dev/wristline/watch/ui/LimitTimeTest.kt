package dev.wristline.watch.ui

import dev.wristline.watch.data.AccountLook
import dev.wristline.watch.data.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Test

class LimitTimeTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    @Test
    fun aPassedResetEndsTheWindow() {
        val line = LimitLine(ProviderId.CODEX, 100, now + minute, 2, account = AccountLook("P", null, "Pro"))
        assertEquals(line, currentLine(line, now))
        // Under a minute left the window still shows.
        assertEquals(line, currentLine(line, now + 59_000))
        // From its reset time: as without usage (a dash, no clock), the account and session count kept.
        assertEquals(LimitLine(ProviderId.CODEX, null, null, 2, account = AccountLook("P", null, "Pro")), currentLine(line, now + minute))
        // Without a reset time nothing ends it.
        val open = line.copy(resetsAt = null)
        assertEquals(open, currentLine(open, now + 365L * 24 * 60 * minute))
    }
}
