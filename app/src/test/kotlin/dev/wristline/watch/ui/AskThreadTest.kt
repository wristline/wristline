package dev.wristline.watch.ui

import dev.wristline.watch.data.Ask
import dev.wristline.watch.data.AskStatus
import dev.wristline.watch.data.ProviderId
import dev.wristline.watch.data.thread
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AskThreadTest {
    private fun ask(id: String, at: String, threadId: String = "") =
        Ask(id, ProviderId.CLAUDE_CODE, "q$id", AskStatus.DONE, createdAt = "2026-09-29T$at:00Z", threadId = threadId)

    // Newest first, as the bridge lists them: b and c continue a's thread, d stands alone.
    private val asks = listOf(
        ask("d", "12:30"),
        ask("c", "12:20", threadId = "a"),
        ask("b", "12:10", threadId = "a"),
        ask("a", "12:00"),
    )

    @Test
    fun threadIsTheThreadIdOrTheOwnId() {
        assertEquals("a", ask("a", "12:00").thread)
        assertEquals("a", ask("b", "12:10", threadId = "a").thread)
    }

    @Test
    fun askThreadListsTheConversationOldestFirstFromAnyOfItsAsks() {
        assertEquals(listOf("a", "b", "c"), askThread(asks, "b")!!.map { it.id })
        assertEquals(listOf("a", "b", "c"), askThread(asks, "a")!!.map { it.id })
        assertEquals(listOf("d"), askThread(asks, "d")!!.map { it.id })
        assertNull(askThread(asks, "z"))
    }

    @Test
    fun askThreadFindsTheConversationByItsIdAfterItsFirstAskIsDropped() {
        // The bridge keeps only its newest asks: a's own ask is gone, its follow-ups are still listed.
        val dropped = asks.filterNot { it.id == "a" }
        assertEquals(listOf("b", "c"), askThread(dropped, "a")!!.map { it.id })
        assertNull(askThread(dropped, "z"))
    }

    @Test
    fun equalTimesKeepTheBridgeOrder() {
        val same = listOf(ask("b", "12:00", threadId = "a"), ask("a", "12:00"))
        assertEquals(listOf("a", "b"), askThread(same, "a")!!.map { it.id })
    }

    @Test
    fun threadsAreGroupedCountedAndOrderedByNewestActivity() {
        val threads = askThreads(asks)
        assertEquals(listOf("d", "a"), threads.map { it.id })
        val conversation = threads[1]
        assertEquals("a", conversation.first.id)
        assertEquals("c", conversation.newest.id)
        assertEquals(3, conversation.count)
        assertEquals(1, threads[0].count)
        // A follow-up newer than the lone ask moves its thread first.
        val moved = listOf(ask("e", "12:40", threadId = "a")) + asks
        assertEquals(listOf("a", "d"), askThreads(moved).map { it.id })
    }

    @Test
    fun ttsLocaleIsKoreanForHangulElseEnglish() {
        assertEquals(Locale.KOREAN, ttsLocale("뮤텍스는 잠금입니다."))
        assertEquals(Locale.KOREAN, ttsLocale("Mutex: 상호 배제 (mutual exclusion)"))
        assertEquals(Locale.US, ttsLocale("A mutex is a lock."))
        assertEquals(Locale.US, ttsLocale(""))
    }
}
