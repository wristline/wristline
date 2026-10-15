package dev.wristline.watch.ui

import dev.wristline.watch.data.Account
import dev.wristline.watch.data.LimitKind
import dev.wristline.watch.data.ProviderId
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.SessionStatus
import dev.wristline.watch.data.Usage
import dev.wristline.watch.data.UsageWindow
import dev.wristline.watch.data.isoToMillis
import java.time.ZoneId
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

    private fun end(kind: String?, usage: List<Usage>, resetsAt: String? = null, estimated: Boolean = false, reportedAt: Long? = now) =
        limitEnd(kind, resetsAt, estimated, reportedAt, session, usage, now)

    // The weekly window (and a bridge time as far off) is long; the 5-hour one and nearer times short.
    private fun at(iso: String, estimated: Boolean = false) = LimitEnd.At(isoToMillis(iso)!!, estimated, long = iso == week)

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
    fun aBridgeResetTimeIsLongFromADayAfterTheLimitWasReported() {
        val day = 24 * 3_600_000L
        val reported = isoToMillis("2026-10-01T09:00:00Z")!!
        assertEquals(false, (end(LimitKind.WINDOW, emptyList(), "2026-10-02T08:59:00Z", reportedAt = reported) as LimitEnd.At).long)
        assertEquals(true, (end(LimitKind.WINDOW, emptyList(), "2026-10-02T09:00:00Z", reportedAt = reported) as LimitEnd.At).long)
        // Without the report's time, from now.
        assertEquals(false, (end(LimitKind.WINDOW, emptyList(), "2026-10-02T10:40:00Z", reportedAt = null) as LimitEnd.At).long)
        assertEquals(LimitEnd.At(now + day, estimated = false, long = true), limitEnd(LimitKind.WINDOW, "2026-10-03T03:00:00Z", false, null, session, emptyList(), now))
    }

    @Test
    fun aResetTimeFromUsageIsLongAsItsWindowIs() {
        // Codex's windows by their length; Claude Code's by id, a model's weekly one too.
        assertEquals(true, (end(LimitKind.WINDOW, listOf(codex(work, 100.0, 100.0))) as LimitEnd.At).long)
        assertEquals(false, (end(LimitKind.WINDOW, listOf(codex(work, 100.0, 40.0))) as LimitEnd.At).long)
        val claude = Session("claude:1", ProviderId.CLAUDE_CODE, status = SessionStatus.IDLE, lastActivity = "2026-10-02T02:59:00Z")
        fun claudeEnd(vararg windows: UsageWindow) =
            limitEnd(LimitKind.WINDOW, null, false, now, claude, listOf(Usage(ProviderId.CLAUDE_CODE, "2026-10-02T02:59:00Z", windows.toList())), now)
        assertEquals(LimitEnd.At(isoToMillis(week)!!, estimated = false, long = true), claudeEnd(UsageWindow("5h", 40.0, fiveHour), UsageWindow("7d_opus", 100.0, week)))
        assertEquals(LimitEnd.At(isoToMillis(fiveHour)!!, estimated = true, long = false), claudeEnd(UsageWindow("5h", 97.0, fiveHour), UsageWindow("7d", 60.0, week)))
    }

    @Test
    fun anErrorThatIsNoLimitHasNoEnd() {
        assertNull(end(null, listOf(codex(work, 100.0, 100.0))))
    }

    @Test
    fun theEndsClockIsTheTimeAloneOnTheItemsDayElseAsOfNow() {
        val seoul = ZoneId.of("Asia/Seoul")
        // Now is 12:00 on Friday 2 October in Seoul; the item is from 19:53 on Thursday 1 October.
        val item = isoToMillis("2026-10-01T10:53:00Z")
        val sameDay = isoToMillis("2026-10-01T14:24:00Z")!!
        assertEquals("11:24 PM", limitEndClockText(sameDay, item, now, seoul, is24Hour = false))
        assertEquals("23:24", limitEndClockText(sameDay, item, now, seoul, is24Hour = true))
        // Another day than the item's: the weekday or date, as of now.
        assertEquals("Oct 1 11:24 PM", limitEndClockText(sameDay, null, now, seoul, is24Hour = false))
        assertEquals("Mon 06:00 PM", limitEndClockText(isoToMillis(week)!!, item, now, seoul, is24Hour = false))
        assertEquals("07:40 PM", limitEndClockText(isoToMillis("2026-10-02T10:40:00Z")!!, item, now, seoul, is24Hour = false))
    }
}
