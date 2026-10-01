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

class UsagePickTest {
    private val at = "2026-09-29T00:00:00Z"
    private val me = Account("acc-me", "me@gmail.com")
    private val school = Account("acc-school", "school", estimated = true)

    private fun claude(account: Account?, fiveHour: Double, week: Double) =
        Usage(ProviderId.CLAUDE_CODE, at, listOf(UsageWindow("5h", fiveHour), UsageWindow("7d", week)), account)

    private fun codex(account: Account?, vararg percents: Double) =
        Usage(ProviderId.CODEX, at, listOf("primary", "secondary").zip(percents.toList()) { id, p -> UsageWindow(id, p) }, account)

    private fun session(provider: String, account: Account?) =
        Session("$provider:1", provider, status = SessionStatus.RUNNING, lastActivity = at, account = account)

    private fun sessions(provider: String, count: Int) =
        List(count) { Session("$provider:$it", provider, status = SessionStatus.RUNNING, lastActivity = at) }

    @Test
    fun limitLinesKeepOneLinePerProviderInProviderOrder() {
        val lines = limitLines(listOf(codex(null, 2.0), claude(me, 14.0, 40.0)), emptyList())
        assertEquals(listOf(ProviderId.CLAUDE_CODE, ProviderId.CODEX), lines.map { it.provider })
    }

    @Test
    fun limitLinesShowTheShortWindow() {
        val lines = limitLines(listOf(claude(me, 14.0, 90.0), codex(me, 12.0, 70.0)), emptyList())
        assertEquals(listOf(14, 12), lines.map { it.percent })
        // By length when reported: here Codex's secondary is the shorter one.
        val reported = Usage(ProviderId.CODEX, at, listOf(UsageWindow("primary", 50.0, minutes = 10_080), UsageWindow("secondary", 5.0, minutes = 300)))
        assertEquals(UsageWindow("secondary", 5.0, minutes = 300), shortWindow(reported))
        // A 5-hour window of unknown length still counts as five hours.
        assertEquals(UsageWindow("5h", 14.0), shortWindow(Usage(ProviderId.CLAUDE_CODE, at, listOf(UsageWindow("7d", 40.0, minutes = 10_080), UsageWindow("5h", 14.0)))))
    }

    @Test
    fun limitLinesPickTheAccountClosestToItsShortWindowLimit() {
        // school's weekly window is the highest of any, but me is closer to its 5-hour limit.
        val a = claude(me, 60.0, 20.0)
        val b = claude(school, 10.0, 85.0)
        assertEquals(60, limitLines(listOf(a, b), emptyList()).single().percent)
        assertEquals(70, limitLines(listOf(a, claude(school, 70.0, 5.0)), emptyList()).single().percent)
        // A tie keeps the first entry, with its reset time.
        val first = Usage(ProviderId.CLAUDE_CODE, at, listOf(UsageWindow("5h", 60.0, "2026-09-29T02:00:00Z")), me)
        assertEquals(isoToMillis("2026-09-29T02:00:00Z"), limitLines(listOf(first, claude(school, 60.0, 5.0)), emptyList()).single().resetsAt)
    }

    @Test
    fun claudeLineIsTheFiveHourWindowOnly() {
        // The 5-hour window gone (reset, the next not reported yet): no percentage, not the weekly 40%.
        val weekOnly = Usage(ProviderId.CLAUDE_CODE, at, listOf(UsageWindow("7d", 40.0, "2026-10-03T09:00:00Z", 10_080)), me)
        assertNull(shortWindow(weekOnly))
        assertEquals(LimitLine(ProviderId.CLAUDE_CODE, null, null, 1), limitLines(listOf(weekOnly), sessions(ProviderId.CLAUDE_CODE, 1)).single())
        // Without sessions either: no line.
        assertEquals(emptyList<LimitLine>(), limitLines(listOf(weekOnly), emptyList()))
        // One account's weekly 70% does not beat another's 5-hour 20%.
        val a = Usage(ProviderId.CLAUDE_CODE, at, listOf(UsageWindow("7d", 70.0)), me)
        assertEquals(20, limitLines(listOf(a, claude(school, 20.0, 5.0)), emptyList()).single().percent)
    }

    @Test
    fun limitLinesCompareWindowsOfOneLengthOnly() {
        // Codex: one account's weekly primary at 70%, another's 5-hour primary at 20%.
        val weekly = Usage(ProviderId.CODEX, at, listOf(UsageWindow("primary", 70.0, minutes = 10_080)), me)
        val fiveHour = Usage(ProviderId.CODEX, at, listOf(UsageWindow("primary", 20.0, minutes = 300)), school)
        assertEquals(20, limitLines(listOf(weekly, fiveHour), emptyList()).single().percent)
        // Of one length the highest still wins.
        assertEquals(70, limitLines(listOf(weekly, fiveHour.copy(windows = listOf(UsageWindow("primary", 20.0, minutes = 10_080)))), emptyList()).single().percent)
    }

    @Test
    fun limitLinesCountEveryAccountsSessions() {
        val lines = limitLines(
            listOf(claude(me, 60.0, 20.0), claude(school, 10.0, 5.0), codex(me, 12.0)),
            sessions(ProviderId.CLAUDE_CODE, 3) + sessions(ProviderId.CODEX, 1),
        )
        assertEquals(listOf(3, 1), lines.map { it.sessions })
        // Usage without sessions: a count of 0.
        assertEquals(0, limitLines(listOf(codex(me, 12.0)), emptyList()).single().sessions)
    }

    @Test
    fun limitLinesWithoutUsageOrResetTime() {
        // Sessions without usage: no percentage and no reset time; a provider with neither has no line.
        val lines = limitLines(emptyList(), sessions(ProviderId.CODEX, 2) + sessions("gemini", 1))
        assertEquals(listOf(LimitLine(ProviderId.CODEX, null, null, 2), LimitLine("gemini", null, null, 1)), lines)
        assertEquals(emptyList<LimitLine>(), limitLines(emptyList(), emptyList()))
        // Entries without windows count as no usage.
        val empty = Usage(ProviderId.CODEX, at, emptyList(), me)
        assertEquals(emptyList<LimitLine>(), limitLines(listOf(empty), emptyList()))
        assertEquals(listOf(LimitLine(ProviderId.CODEX, 12, null, 0)), limitLines(listOf(empty, codex(school, 12.0, 40.0)), emptyList()))
        // Usage without a reset time keeps its percentage.
        assertEquals(LimitLine(ProviderId.CLAUDE_CODE, 42, null, 0), limitLines(listOf(claude(me, 41.6, 3.0)), emptyList()).single())
    }

    @Test
    fun claudeLimitIsTheFiveHourWindowOfTheSessionsAccount() {
        val usage = listOf(claude(me, 14.0, 40.0), claude(school, 70.0, 5.0))
        assertEquals(UsageWindow("5h", 14.0), sessionLimit(session(ProviderId.CLAUDE_CODE, me), usage))
        assertEquals(UsageWindow("5h", 70.0), sessionLimit(session(ProviderId.CLAUDE_CODE, school), usage))
        // Two accounts and none matching: no guess.
        assertNull(sessionLimit(session(ProviderId.CLAUDE_CODE, null), usage))
        // One entry for the provider is used even when the accounts are unknown.
        assertEquals(UsageWindow("5h", 14.0), sessionLimit(session(ProviderId.CLAUDE_CODE, null), usage.take(1)))
        assertEquals(UsageWindow("5h", 14.0), sessionLimit(session(ProviderId.CLAUDE_CODE, school), listOf(claude(null, 14.0, 40.0))))
    }

    @Test
    fun codexLimitIsThePrimaryWindow() {
        val usage = listOf(claude(me, 14.0, 40.0), codex(me, 12.0, 40.0))
        assertEquals(UsageWindow("primary", 12.0), sessionLimit(session(ProviderId.CODEX, me), usage))
        assertNull(sessionLimit(session(ProviderId.CODEX, me), usage.take(1)))
    }
}
