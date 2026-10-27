package dev.wristline.watch.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarningsTest {
    private val now = Instant.parse("2026-10-27T10:00:00Z").toEpochMilli()
    private val reset = "2026-10-27T12:40:00Z"
    private val nextReset = "2026-10-27T17:40:00Z"

    private fun usage(percent: Double, resetsAt: String? = reset, id: String = "5h", account: Account? = null) =
        Usage(ProviderId.CLAUDE_CODE, updatedAt = "2026-10-27T09:59:00Z", windows = listOf(UsageWindow(id, percent, resetsAt, minutes = 300)), account = account)

    private fun session(used: Long, id: String = "claude-code:s1", status: String = SessionStatus.IDLE) =
        Session(id, ProviderId.CLAUDE_CODE, status = status, lastActivity = "2026-10-27T09:59:00Z", context = ContextUsage(used, 200_000))

    @Test
    fun limitFiresOnceWhenItCrossesEighty() {
        val warnings = Warnings()
        assertEquals(emptyList<Warning>(), warnings.check(listOf(usage(79.0)), emptyList(), now))
        val due = warnings.check(listOf(usage(82.0)), emptyList(), now)
        assertEquals(listOf(Warning.Limit(usage(82.0), usage(82.0).windows[0])), due)
        assertEquals(emptyList<Warning>(), warnings.check(listOf(usage(90.0)), emptyList(), now))
        // Codex jitters the reset time by about a second: the same window.
        assertEquals(emptyList<Warning>(), warnings.check(listOf(usage(91.0, "2026-10-27T12:40:01Z")), emptyList(), now))
    }

    @Test
    fun limitRearmsInTheNextCycle() {
        val warnings = Warnings()
        assertEquals(1, warnings.check(listOf(usage(85.0)), emptyList(), now).size)
        // The window reset (a new reset time) and filled up again.
        assertEquals(1, warnings.check(listOf(usage(81.0, nextReset)), emptyList(), now).size)
        assertEquals(emptyList<Warning>(), warnings.check(listOf(usage(84.0, nextReset)), emptyList(), now))
    }

    @Test
    fun limitWithoutAResetTimeRearmsOnceBelowEighty() {
        val warnings = Warnings()
        assertEquals(1, warnings.check(listOf(usage(85.0, resetsAt = null)), emptyList(), now).size)
        assertEquals(emptyList<Warning>(), warnings.check(listOf(usage(86.0, resetsAt = null)), emptyList(), now))
        warnings.check(listOf(usage(5.0, resetsAt = null)), emptyList(), now)
        assertEquals(1, warnings.check(listOf(usage(80.0, resetsAt = null)), emptyList(), now).size)
    }

    @Test
    fun fullAndExpiredWindowsAreSkipped() {
        val warnings = Warnings()
        // At 100 the bridge's own limit alert speaks.
        assertEquals(emptyList<Warning>(), warnings.check(listOf(usage(100.0)), emptyList(), now))
        // Past its reset time the percentage is old.
        assertEquals(emptyList<Warning>(), warnings.check(listOf(usage(90.0, "2026-10-27T09:00:00Z")), emptyList(), now))
        assertEquals(emptySet<String>(), warnings.fired)
    }

    @Test
    fun eachAccountWarnsOnItsOwn() {
        val warnings = Warnings()
        val work = usage(85.0, account = Account("w", "Work"))
        val pro = usage(85.0, account = Account("p", "Pro"))
        assertEquals(2, warnings.check(listOf(work, pro), emptyList(), now).size)
    }

    @Test
    fun contextFiresAtEightyFiveAndRearmsBelowSeventy() {
        val warnings = Warnings()
        assertEquals(emptyList<Warning>(), warnings.check(emptyList(), listOf(session(168_000)), now))
        assertEquals(listOf(Warning.Context(session(174_000), 87)), warnings.check(emptyList(), listOf(session(174_000)), now))
        assertEquals(emptyList<Warning>(), warnings.check(emptyList(), listOf(session(190_000)), now))
        // Between 70 and 85 after it: still silent.
        assertEquals(emptyList<Warning>(), warnings.check(emptyList(), listOf(session(150_000)), now))
        assertEquals(emptyList<Warning>(), warnings.check(emptyList(), listOf(session(175_000)), now))
        // Compacted below 70: warns again on the next climb.
        warnings.check(emptyList(), listOf(session(60_000)), now)
        assertEquals(1, warnings.check(emptyList(), listOf(session(171_000)), now).size)
    }

    @Test
    fun contextRearmsWhenTheSessionGoesAndEndedOnesAreIgnored() {
        val warnings = Warnings()
        assertEquals(1, warnings.check(emptyList(), listOf(session(180_000)), now).size)
        warnings.check(emptyList(), emptyList(), now)
        assertEquals(1, warnings.check(emptyList(), listOf(session(180_000)), now).size)
        assertEquals(emptyList<Warning>(), warnings.check(emptyList(), listOf(session(180_000, id = "claude-code:s2", status = SessionStatus.ENDED)), now))
    }

    @Test
    fun firedKeysCarryOverARestartAndArePrunedAfterTheReset() {
        val first = Warnings()
        first.check(listOf(usage(85.0)), listOf(session(180_000)), now)
        val restarted = Warnings(first.fired)
        assertEquals(emptyList<Warning>(), restarted.check(listOf(usage(86.0)), listOf(session(181_000)), now))
        // Past the reset time the key goes.
        val later = Instant.parse("2026-10-27T13:00:00Z").toEpochMilli()
        restarted.check(emptyList(), listOf(session(181_000)), later)
        assertEquals(1, restarted.fired.size)
        assertTrue(restarted.fired.single().startsWith("context"))
    }

    @Test
    fun offWarnsOfNothing() {
        val warnings = Warnings()
        assertEquals(emptyList<Warning>(), warnings.check(listOf(usage(90.0)), listOf(session(190_000)), now, enabled = false))
        assertEquals(emptySet<String>(), warnings.fired)
    }

    @Test
    fun firedKeysAreCapped() {
        val warnings = Warnings()
        val many = (1..40).map { usage(85.0, account = Account("a$it", "A$it")) }
        assertEquals(40, warnings.check(many, emptyList(), now).size)
        assertEquals(32, warnings.fired.size)
    }
}
