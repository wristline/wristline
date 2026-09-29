package dev.wristline.watch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionItemsTest {
    private fun item(seq: Long, text: String = "t$seq") = Item(seq, ItemKind.ASSISTANT, "2026-09-29T00:00:00Z", text)

    private fun seqs(items: SessionItems) = items.items.map { it.seq }

    private fun range(from: Long, to: Long) = (from..to).map { item(it) }

    @Test
    fun liveItemsAppendReplaceAndInsert() {
        var s = SessionItems(items = listOf(item(1), item(3)), loaded = true)
        s = s.withItem(item(4))
        s = s.withItem(item(3, "updated"))
        s = s.withItem(item(2))
        assertEquals(listOf(1L, 2L, 3L, 4L), seqs(s))
        assertEquals("updated", s.items[2].text)
    }

    @Test
    fun itemsOlderThanTheLoadedRangeAreLeftToEarlierPages() {
        val s = SessionItems(items = listOf(item(10)), loaded = true).withItem(item(5))
        assertEquals(listOf(10L), seqs(s))
    }

    @Test
    fun capDropsTheOldestAndMarksMoreHistory() {
        var s = SessionItems(items = range(1, ITEM_CAP.toLong()), loaded = true)
        s = s.withItem(item(ITEM_CAP + 1L))
        assertEquals(ITEM_CAP, s.items.size)
        assertEquals(2L, s.items.first().seq)
        assertTrue(s.hasMore)
        assertFalse(s.canLoadEarlier)
    }

    @Test
    fun latestPageMergesWhenContiguousAndReplacesAfterAGap() {
        val held = SessionItems(items = range(1, 50), hasMore = false, loaded = true)
        val merged = held.withLatest(ItemPage(range(41, 80), hasMore = true))
        assertEquals((1L..80L).toList(), seqs(merged))
        assertFalse("older side comes from what was held", merged.hasMore)

        val replaced = held.withLatest(ItemPage(range(100, 139), hasMore = true))
        assertEquals((100L..139L).toList(), seqs(replaced))
        assertTrue(replaced.hasMore)
        assertTrue(replaced.loaded)
        assertFalse(replaced.loading)
    }

    @Test
    fun latestPageClearsAFailedFetch() {
        // Nothing held and the first fetch failed: the screen shows the failure caption until a page arrives.
        val failed = SessionItems(failed = true)
        assertTrue(failed.failed)
        assertFalse(failed.loaded)
        val recovered = failed.withLatest(ItemPage(range(1, 3), hasMore = false))
        assertFalse(recovered.failed)
        assertTrue(recovered.loaded)
        assertEquals(listOf(1L, 2L, 3L), seqs(recovered))
        // A live item does not stand in for the page: the flag stays until the fetch succeeds.
        assertTrue(failed.withItem(item(7)).failed)
    }

    @Test
    fun earlierPagesPrependUpToTheCap() {
        val once = SessionItems(items = range(161, 200), hasMore = true, loaded = true)
            .withEarlier(ItemPage(range(121, 160), hasMore = true))
        assertEquals((121L..200L).toList(), seqs(once))
        assertTrue(once.canLoadEarlier)

        // Overlapping items in the page are ignored; the history ends here.
        val complete = SessionItems(items = range(21, 200), hasMore = true, loaded = true)
            .withEarlier(ItemPage(range(1, 25), hasMore = false))
        assertEquals((1L..200L).toList(), seqs(complete))
        assertFalse(complete.hasMore)

        // Only the newest part of the page fits under the cap.
        val clipped = SessionItems(items = range(61, 250), hasMore = true, loaded = true)
            .withEarlier(ItemPage(range(21, 60), hasMore = false))
        assertEquals(ITEM_CAP, clipped.items.size)
        assertEquals(51L, clipped.items.first().seq)
        assertTrue("dropped older items are still on the bridge", clipped.hasMore)
        assertFalse(clipped.canLoadEarlier)
    }

    @Test
    fun sessionsSortByStatusThenRecency() {
        fun session(id: String, status: String, at: String) =
            Session(id = id, provider = ProviderId.CODEX, status = status, lastActivity = at)
        val sorted = sortSessions(
            listOf(
                session("idle-new", SessionStatus.IDLE, "2026-09-29T12:00:00Z"),
                session("ended", SessionStatus.ENDED, "2026-09-29T13:00:00Z"),
                session("running", SessionStatus.RUNNING, "2026-09-29T10:00:00Z"),
                session("waiting", SessionStatus.NEEDS_INPUT, "2026-09-29T09:00:00Z"),
                session("idle-old", SessionStatus.IDLE, "2026-09-29T11:00:00Z"),
            ),
        )
        assertEquals(listOf("waiting", "running", "idle-new", "idle-old", "ended"), sorted.map { it.id })
    }
}
