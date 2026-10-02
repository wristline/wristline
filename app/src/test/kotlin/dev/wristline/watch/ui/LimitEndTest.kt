package dev.wristline.watch.ui

import dev.wristline.watch.data.Account
import dev.wristline.watch.data.LimitKind
import dev.wristline.watch.data.ProviderId
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.SessionStatus
import dev.wristline.watch.data.Usage
import dev.wristline.watch.data.UsageWindow
import dev.wristline.watch.data.isoToMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LimitEndTest {
    private val now = isoToMillis("2026-10-02T03:00:00Z")!!
    private val work = Account("acc-work", "Work")
    private val home = Account("acc-home", "Home")
    private val fiveHour = "2026-10-02T05:30:00Z"
    private val week = "2026-10-05T09:00:00Z"
    private val session = Session("codex:1", ProviderId.CODEX, status = SessionStatus.IDLE, lastActivity = "2026-10-02T02:59:00Z", account = work)

    private fun codex(account: Account?, primary: Double, secondary: Double, primaryResets: String = fiveHour, secondaryResets: String = week) =
        Usage(ProviderId.CODEX, "2026-10-02T02:59:00Z", listOf(UsageWindow("primary", primary, primaryResets, 300), UsageWindow("secondary", secondary, secondaryResets, 10080)), account)

    private fun end(kind: String?, usage: List<Usage>, resetsAt: String? = null, estimated: Boolean = false) =
        limitEnd(kind, resetsAt, estimated, session, usage, now)

    private fun at(iso: String, estimated: Boolean = false) = LimitEnd.At(isoToMillis(iso)!!, estimated)

    @Test
    fun theBridgesResetTimeWins() {
        val full = listOf(codex(work, 100.0, 100.0))
        assertEquals(at("2026-10-02T10:40:00Z"), end(LimitKind.WINDOW, full, "2026-10-02T10:40:00Z"))
        assertEquals(at("2026-10-02T10:40:00Z", estimated = true), end(LimitKind.WINDOW, full, "2026-10-02T10:40:00Z", estimated = true))
        // A bridge before limitKind sends a reset time only for a limit.
        assertEquals(at("2026-10-02T10:40:00Z"), end(null, emptyList(), "2026-10-02T10:40:00Z"))
    }

    @Test
    fun otherwiseTheFullWindowOfTheSessionsAccountThatResetsLatest() {
        assertEquals(at(fiveHour), end(LimitKind.WINDOW, listOf(codex(work, 100.0, 40.0))))
        assertEquals(at(week), end(LimitKind.WINDOW, listOf(codex(work, 100.0, 100.0))))
        // Not another account's.
        assertNull(end(LimitKind.WINDOW, listOf(codex(home, 100.0, 100.0), codex(work, 30.0, 40.0))))
        assertEquals(at(fiveHour), end(LimitKind.WINDOW, listOf(codex(home, 100.0, 100.0), codex(work, 100.0, 40.0))))
        // A full window whose reset has passed is over.
        assertNull(end(LimitKind.WINDOW, listOf(codex(work, 100.0, 40.0, primaryResets = "2026-10-02T02:00:00Z"))))
    }

    @Test
    fun withoutAFullWindowTheFullestFromNinetyFivePercentIsAnEstimateElseNothing() {
        assertEquals(at(week, estimated = true), end(LimitKind.WINDOW, listOf(codex(work, 96.0, 98.0))))
        assertNull(end(LimitKind.WINDOW, listOf(codex(work, 94.0, 60.0))))
        assertNull(end(LimitKind.WINDOW, emptyList()))
    }

    @Test
    fun creditsUnlessAWindowIsFull() {
        // Codex reports a used-up weekly window as workspace credits running out.
        assertEquals(at(week), end(LimitKind.CREDITS, listOf(codex(work, 30.0, 100.0))))
        assertEquals(LimitEnd.Credits, end(LimitKind.CREDITS, listOf(codex(work, 98.0, 22.0))))
        assertEquals(LimitEnd.Credits, end(LimitKind.CREDITS, emptyList()))
    }

    @Test
    fun anErrorThatIsNoLimitHasNoEnd() {
        assertNull(end(null, listOf(codex(work, 100.0, 100.0))))
    }
}
