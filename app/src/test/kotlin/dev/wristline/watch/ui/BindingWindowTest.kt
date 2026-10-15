package dev.wristline.watch.ui

import dev.wristline.watch.data.Account
import dev.wristline.watch.data.ProviderId
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.SessionStatus
import dev.wristline.watch.data.Usage
import dev.wristline.watch.data.UsageWindow
import dev.wristline.watch.data.isoToMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BindingWindowTest {
    private val at = "2026-10-15T00:00:00Z"
    private val now = isoToMillis(at)!!
    private val fiveHourReset = "2026-10-15T03:00:00Z"
    private val weekReset = "2026-10-17T09:00:00Z"

    private fun fiveHour(percent: Double, resetsAt: String? = fiveHourReset) = UsageWindow("5h", percent, resetsAt, 300, "5h")
    private fun week(percent: Double, resetsAt: String? = weekReset) = UsageWindow("7d", percent, resetsAt, 10_080, "7d")

    @Test
    fun theFullerWindowBinds() {
        assertEquals(week(70.0), bindingWindow(listOf(fiveHour(40.0), week(70.0)), now))
        assertEquals(fiveHour(100.0), bindingWindow(listOf(fiveHour(100.0), week(70.0)), now))
        // Codex's windows by the same rule.
        val primary = UsageWindow("primary", 12.0, fiveHourReset, 300)
        val secondary = UsageWindow("secondary", 31.0, weekReset, 10_080)
        assertEquals(secondary, bindingWindow(listOf(primary, secondary), now))
    }

    @Test
    fun ofEqualsTheLaterResetBinds() {
        assertEquals(week(50.0), bindingWindow(listOf(fiveHour(50.0), week(50.0)), now))
        assertEquals(week(50.0), bindingWindow(listOf(week(50.0), fiveHour(50.0)), now))
        // A window without a reset time loses the tie, but not a higher percentage.
        assertEquals(fiveHour(50.0), bindingWindow(listOf(week(50.0, resetsAt = null), fiveHour(50.0)), now))
        assertEquals(week(51.0, resetsAt = null), bindingWindow(listOf(week(51.0, resetsAt = null), fiveHour(50.0)), now))
    }

    @Test
    fun aWindowPastItsResetNoLongerBinds() {
        val windows = listOf(fiveHour(100.0), week(70.0))
        assertEquals(fiveHour(100.0), bindingWindow(windows, isoToMillis(fiveHourReset)!! - 1))
        // From the 5-hour reset on, the weekly window is the fuller one, without a new report.
        assertEquals(week(70.0), bindingWindow(windows, isoToMillis(fiveHourReset)!!))
        // Every window over: none.
        assertNull(bindingWindow(windows, isoToMillis(weekReset)!!))
        // Without a reset time nothing ends a window.
        assertEquals(week(70.0, resetsAt = null), bindingWindow(listOf(week(70.0, resetsAt = null)), now + 365L * 24 * 3_600_000))
    }

    @Test
    fun aModelsWeeklyWindowCountsAndSpendDoesNot() {
        val opus = UsageWindow("7d_opus", 82.0, weekReset, 10_080, "7d Opus")
        assertEquals(opus, bindingWindow(listOf(fiveHour(40.0), week(70.0), opus), now))
        val spend = UsageWindow("spend", 99.0, label = "Spend")
        assertEquals(week(70.0), bindingWindow(listOf(spend, fiveHour(40.0), week(70.0)), now))
        assertNull(bindingWindow(listOf(spend), now))
    }

    @Test
    fun noWindowsNoneBinds() {
        assertNull(bindingWindow(emptyList(), now))
    }

    @Test
    fun theCardAndTheGaugeShowTheSameWindow() {
        val me = Account("acc-me", "me@gmail.com", primary = true)
        val entry = Usage(ProviderId.CLAUDE_CODE, at, listOf(fiveHour(40.0), week(70.0)), me)
        val session = Session("claude-code:1", ProviderId.CLAUDE_CODE, status = SessionStatus.RUNNING, lastActivity = at, account = me)
        val line = limitLines(listOf(entry), listOf(session)).single()
        for (time in listOf(now, isoToMillis(fiveHourReset)!!, isoToMillis(weekReset)!!)) {
            assertEquals(sessionLimit(session, listOf(entry), time), bindingWindow(line.windows, time))
        }
        assertEquals(week(70.0), sessionLimit(session, listOf(entry), now))
    }
}
