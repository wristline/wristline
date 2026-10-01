package dev.wristline.watch.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageEventTest {
    private val at = "2026-09-29T00:00:00Z"
    private val me = Account("acc-me", "me@gmail.com")
    private val school = Account("acc-school", "school")

    private fun claude(account: Account?, percent: Double) = Usage(ProviderId.CLAUDE_CODE, at, listOf(UsageWindow("5h", percent)), account)

    @Test
    fun anEventReplacesTheEntryWithItsKeyOrIsAdded() {
        val list = listOf(claude(me, 10.0), claude(school, 20.0))
        assertEquals(listOf(claude(me, 30.0), claude(school, 20.0)), list.withUsage(claude(me, 30.0)))
        assertEquals(list + claude(null, 5.0), list.withUsage(claude(null, 5.0)))
    }

    @Test
    fun anEventWithoutWindowsRemovesTheEntryWithItsKey() {
        val list = listOf(claude(me, 10.0), claude(school, 20.0), claude(null, 5.0))
        val removed = Usage(ProviderId.CLAUDE_CODE, "2026-09-29T00:05:00Z", emptyList(), me)
        assertEquals(listOf(claude(school, 20.0), claude(null, 5.0)), list.withUsage(removed))
        // The entry without an account has its own key.
        assertEquals(listOf(claude(me, 10.0), claude(school, 20.0)), list.withUsage(Usage(ProviderId.CLAUDE_CODE, at)))
        // Another provider's entry with the same account is a different entry.
        assertEquals(list, list.withUsage(Usage(ProviderId.CODEX, at, account = me)))
        // Nothing to remove: no new entry with no windows either.
        assertEquals(emptyList<Usage>(), emptyList<Usage>().withUsage(removed))
    }

    @Test
    fun theRemovalFixtureRemovesItsEntry() {
        // Gradle runs unit tests with the module directory as the working directory.
        val event = parseServerEvent(File("src/main/assets/protocol/event-usage-removed.json").readText()) as ServerEvent.UsageChanged
        val removed = event.usage
        assertEquals(emptyList<UsageWindow>(), removed.windows)
        val kept = claude(me, 10.0)
        val gone = Usage(removed.provider, at, listOf(UsageWindow("primary", 40.0)), removed.account!!.copy(label = "old label"))
        assertEquals(listOf(kept), listOf(gone, kept).withUsage(removed))
    }
}
