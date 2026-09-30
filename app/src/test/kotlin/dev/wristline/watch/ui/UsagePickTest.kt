package dev.wristline.watch.ui

import dev.wristline.watch.data.Account
import dev.wristline.watch.data.ProviderId
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.SessionStatus
import dev.wristline.watch.data.Usage
import dev.wristline.watch.data.UsageWindow
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

    @Test
    fun glanceKeepsOneEntryPerProviderInProviderOrder() {
        val picked = glanceUsage(listOf(codex(null, 2.0), claude(me, 14.0, 40.0)))
        assertEquals(listOf(ProviderId.CLAUDE_CODE, ProviderId.CODEX), picked.map { it.provider })
    }

    @Test
    fun glancePicksTheAccountClosestToALimit() {
        // school's 5h is lower, but its weekly window is the highest percentage of any window.
        val a = claude(me, 60.0, 20.0)
        val b = claude(school, 10.0, 85.0)
        assertEquals(listOf(b), glanceUsage(listOf(a, b)))
        assertEquals(listOf(a), glanceUsage(listOf(a, claude(school, 10.0, 30.0))))
        // A tie keeps the first entry.
        assertEquals(listOf(a), glanceUsage(listOf(a, claude(school, 60.0, 5.0))))
    }

    @Test
    fun glanceSkipsEntriesWithoutWindows() {
        val empty = Usage(ProviderId.CODEX, at, emptyList(), me)
        assertEquals(emptyList<Usage>(), glanceUsage(listOf(empty)))
        val codex = codex(school, 12.0, 40.0)
        assertEquals(listOf(codex), glanceUsage(listOf(empty, codex)))
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
