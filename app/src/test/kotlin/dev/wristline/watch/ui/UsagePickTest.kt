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

    private val basic = Account("chatgpt-basic", "기본")
    private val pro = Account("chatgpt-pro", "Pro")

    private fun session(provider: String, id: Int, account: Account?) =
        Session("$provider:$id", provider, status = SessionStatus.RUNNING, lastActivity = at, account = account)

    @Test
    fun limitLinesAreOnePerAccountInProviderOrder() {
        val lines = limitLines(listOf(codex(pro, 3.0), codex(basic, 11.0), claude(me, 42.0, 10.0)), emptyList())
        assertEquals(listOf(ProviderId.CLAUDE_CODE, ProviderId.CODEX, ProviderId.CODEX), lines.map { it.provider })
        // The account labelled as the default first, whatever order the bridge sent them in.
        assertEquals(listOf(42, 11, 3), lines.map { it.percent })
        assertEquals(listOf(null, "기본", "Pro"), lines.map { it.account })
    }

    @Test
    fun usageOrderPutsTheDefaultAccountFirstThenTheRestByLabel() {
        val zed = Account("z", "zed")
        val alpha = Account("a", "alpha")
        val order = usageOrder(listOf(codex(zed, 1.0), codex(pro, 1.0), claude(me, 1.0, 1.0), codex(basic, 1.0), codex(alpha, 1.0)))
        assertEquals(listOf("me@gmail.com", "기본", "Pro", "alpha", "zed"), order.map { it.account?.label })
        // `default` in any case is the default too; an entry without an account comes first.
        val english = usageOrder(listOf(codex(pro, 1.0), codex(Account("d", "Default"), 1.0), codex(null, 1.0)))
        assertEquals(listOf(null, "Default", "Pro"), english.map { it.account?.label })
    }

    @Test
    fun accountsAreMarkedOnlyWhenTheirProviderHasMoreThanOneLine() {
        val lines = limitLines(listOf(claude(me, 42.0, 10.0), codex(basic, 11.0), codex(pro, 3.0)), emptyList())
        assertEquals(listOf(null, "기", "P"), lines.map { line -> line.account?.let(::accountMark) })
        // One account: no mark, and the line reads as the provider's alone.
        assertEquals(listOf<String?>(null), limitLines(listOf(codex(pro, 3.0)), emptyList()).map { it.account })
        // The first character as given, a surrogate pair kept whole.
        assertEquals("p", accountMark(" personal"))
        assertEquals("\uD83D\uDE80", accountMark("\uD83D\uDE80 rocket"))
        assertEquals("", accountMark(" "))
    }

    @Test
    fun limitLinesCountSessionsByAccount() {
        val sessions = listOf(
            session(ProviderId.CODEX, 1, pro),
            session(ProviderId.CODEX, 2, pro),
            session(ProviderId.CODEX, 3, basic),
            // Without an account, or of an account without a line: on the provider's first line.
            session(ProviderId.CODEX, 4, null),
            session(ProviderId.CODEX, 5, Account("old", "old login")),
            session(ProviderId.CLAUDE_CODE, 6, null),
        )
        val lines = limitLines(listOf(codex(pro, 3.0), codex(basic, 11.0), claude(me, 42.0, 10.0)), sessions)
        assertEquals(listOf(1, 3, 2), lines.map { it.sessions })
        assertEquals(sessions.size, lines.sumOf { it.sessions })
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
        // Each account its own: a weekly primary on one line, a 5-hour one on the other.
        val weekly = Usage(ProviderId.CODEX, at, listOf(UsageWindow("primary", 11.0, "2026-10-05T09:00:00Z", 10_080)), basic)
        val fiveHour = Usage(
            ProviderId.CODEX, at,
            listOf(UsageWindow("primary", 3.0, "2026-09-29T15:10:00Z", 300), UsageWindow("secondary", 20.0, "2026-10-05T13:00:00Z", 10_080)),
            pro,
        )
        assertEquals(
            listOf(
                LimitLine(ProviderId.CODEX, 11, isoToMillis("2026-10-05T09:00:00Z"), 0, "기본"),
                LimitLine(ProviderId.CODEX, 3, isoToMillis("2026-09-29T15:10:00Z"), 0, "Pro"),
            ),
            limitLines(listOf(fiveHour, weekly), emptyList()),
        )
    }

    @Test
    fun claudeLineIsTheFiveHourWindowOnly() {
        // The 5-hour window gone (reset, the next not reported yet): no percentage, not the weekly 40%.
        val weekOnly = Usage(ProviderId.CLAUDE_CODE, at, listOf(UsageWindow("7d", 40.0, "2026-10-03T09:00:00Z", 10_080)), me)
        assertNull(shortWindow(weekOnly))
        assertEquals(LimitLine(ProviderId.CLAUDE_CODE, null, null, 1), limitLines(listOf(weekOnly), sessions(ProviderId.CLAUDE_CODE, 1)).single())
        // The account keeps its line without sessions too, as a dash.
        assertEquals(listOf(LimitLine(ProviderId.CLAUDE_CODE, null, null, 0)), limitLines(listOf(weekOnly), emptyList()))
        // Beside another account's line it is still its own.
        assertEquals(listOf(null, 20), limitLines(listOf(weekOnly, claude(school, 20.0, 5.0)), emptyList()).map { it.percent })
    }

    @Test
    fun limitLinesWithoutUsageOrResetTime() {
        // Sessions without usage: one line per provider, no percentage and no reset time; a provider with neither has no line.
        val lines = limitLines(emptyList(), sessions(ProviderId.CODEX, 2) + sessions("gemini", 1))
        assertEquals(listOf(LimitLine(ProviderId.CODEX, null, null, 2), LimitLine("gemini", null, null, 1)), lines)
        assertEquals(emptyList<LimitLine>(), limitLines(emptyList(), emptyList()))
        // Entries without windows count as none (the bridge removes such an entry).
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
