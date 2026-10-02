package dev.wristline.watch

import dev.wristline.watch.data.ProviderId
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.SessionStatus
import dev.wristline.watch.data.TaskProgress
import dev.wristline.watch.data.isoToMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUpdatesTest {
    private val turn = "2026-10-02T12:00:00Z"
    private val start = isoToMillis(turn)!!

    private fun session(
        status: String = SessionStatus.RUNNING,
        turnStartedAt: String? = turn,
        progress: TaskProgress? = null,
        id: String = "claude-code:1",
    ) = Session(id, ProviderId.CLAUDE_CODE, title = "Fix CI", status = status, lastActivity = turn, turnStartedAt = turnStartedAt, progress = progress)

    /** Running without a task list, it counts the minutes: one, at the first post. */
    private fun update(progress: TaskProgress? = null, waiting: Boolean = false, id: String = "claude-code:1", minutes: Int? = if (progress == null && !waiting) 1 else null) =
        LiveUpdate(id, turn, start, "Fix CI", progress, waiting, minutes)

    @Test
    fun dueAfterAMinuteOrWithATaskListOfTwo() {
        assertFalse(liveUpdateDue(session(), start + LIVE_UPDATE_AFTER_MS - 1))
        assertTrue(liveUpdateDue(session(), start + LIVE_UPDATE_AFTER_MS))
        assertTrue(liveUpdateDue(session(progress = TaskProgress(0, 2)), start + 1_000))
        assertFalse(liveUpdateDue(session(progress = TaskProgress(0, 1)), start + 1_000))
        // No turn start: only a task list makes it due.
        assertFalse(liveUpdateDue(session(turnStartedAt = null), start + 10 * LIVE_UPDATE_AFTER_MS))
        assertTrue(liveUpdateDue(session(turnStartedAt = null, progress = TaskProgress(1, 3)), start))
    }

    @Test
    fun onlyRunningSessionsAreDue() {
        val late = start + 10 * LIVE_UPDATE_AFTER_MS
        for (status in listOf(SessionStatus.IDLE, SessionStatus.NEEDS_INPUT, SessionStatus.ENDED)) {
            assertFalse(status, liveUpdateDue(session(status = status, progress = TaskProgress(1, 5)), late))
        }
    }

    @Test
    fun shortTurnNeverPostsAndSaysWhenToLookAgain() {
        val live = LiveUpdates()
        assertEquals(emptyList<LiveChange>(), live.plan(listOf(session()), start + 5_000))
        assertEquals(start + LIVE_UPDATE_AFTER_MS, live.nextDueAt(listOf(session()), start + 5_000))
        // It ends before the minute: nothing posted, nothing to cancel.
        assertEquals(emptyList<LiveChange>(), live.plan(listOf(session(status = SessionStatus.IDLE, turnStartedAt = null)), start + 20_000))
        assertNull(live.nextDueAt(listOf(session(status = SessionStatus.IDLE, turnStartedAt = null)), start + 20_000))
    }

    @Test
    fun postsUpdatesAndCancels() {
        val live = LiveUpdates()
        val t = start + LIVE_UPDATE_AFTER_MS
        assertEquals(listOf(LiveChange.Post(update())), live.plan(listOf(session()), t))
        // Next due only for its next minute.
        assertEquals(t + LIVE_UPDATE_AFTER_MS, live.nextDueAt(listOf(session()), t))
        // Unchanged: nothing to do.
        assertEquals(emptyList<LiveChange>(), live.plan(listOf(session()), t + 5_000))
        // The task list appears and moves on.
        assertEquals(listOf(LiveChange.Post(update(TaskProgress(1, 4)))), live.plan(listOf(session(progress = TaskProgress(1, 4))), t + 6_000))
        assertEquals(listOf(LiveChange.Post(update(TaskProgress(2, 4)))), live.plan(listOf(session(progress = TaskProgress(2, 4))), t + 8_000))
        // Waiting keeps it, marked, with the last progress when none is sent.
        assertEquals(
            listOf(LiveChange.Post(update(TaskProgress(2, 4), waiting = true))),
            live.plan(listOf(session(status = SessionStatus.NEEDS_INPUT, turnStartedAt = null)), t + 10_000),
        )
        // Running again with the same turn: kept, back to running.
        assertEquals(listOf(LiveChange.Post(update(TaskProgress(3, 4)))), live.plan(listOf(session(progress = TaskProgress(3, 4))), t + 12_000))
        // Done: cancelled.
        assertEquals(listOf(LiveChange.Cancel("claude-code:1")), live.plan(listOf(session(status = SessionStatus.IDLE, turnStartedAt = null)), t + 14_000))
    }

    @Test
    fun waitingWithoutALiveUpdateGetsNone() {
        val live = LiveUpdates()
        assertEquals(emptyList<LiveChange>(), live.plan(listOf(session(status = SessionStatus.NEEDS_INPUT, progress = TaskProgress(1, 5))), start + 10 * LIVE_UPDATE_AFTER_MS))
    }

    @Test
    fun oneLiveUpdatePerSessionAndRemovedOnesAreCancelled() {
        val live = LiveUpdates()
        val t = start + LIVE_UPDATE_AFTER_MS
        val a = session(id = "a")
        val b = session(id = "b", progress = TaskProgress(0, 3))
        assertEquals(listOf(LiveChange.Post(update(id = "a")), LiveChange.Post(update(TaskProgress(0, 3), id = "b"))), live.plan(listOf(a, b), t))
        assertEquals(listOf(LiveChange.Cancel("a")), live.plan(listOf(b), t + 2_000))
        // Turned off: everything goes.
        assertEquals(listOf(LiveChange.Cancel("b")), live.plan(emptyList(), t + 4_000))
    }

    @Test
    fun dismissedStaysAwayForTheTurn() {
        val live = LiveUpdates()
        val t = start + LIVE_UPDATE_AFTER_MS
        live.plan(listOf(session()), t)
        live.dismiss("claude-code:1", turn)
        assertEquals(emptyList<LiveChange>(), live.plan(listOf(session(progress = TaskProgress(1, 3))), t + 2_000))
        assertNull(live.nextDueAt(listOf(session()), t + 2_000))
        // Waiting and running again in the same turn: still away.
        assertEquals(emptyList<LiveChange>(), live.plan(listOf(session(status = SessionStatus.NEEDS_INPUT, turnStartedAt = null)), t + 4_000))
        assertEquals(emptyList<LiveChange>(), live.plan(listOf(session()), t + 6_000))
        // A new turn shows again once due.
        val next = "2026-10-02T12:10:00Z"
        val nextStart = isoToMillis(next)!!
        assertEquals(emptyList<LiveChange>(), live.plan(listOf(session(turnStartedAt = next)), nextStart + 1_000))
        assertEquals(
            listOf(LiveChange.Post(LiveUpdate("claude-code:1", next, nextStart, "Fix CI", null, false, minutes = 1))),
            live.plan(listOf(session(turnStartedAt = next)), nextStart + LIVE_UPDATE_AFTER_MS),
        )
    }

    @Test
    fun dismissalEndsWhenTheSessionStops() {
        val live = LiveUpdates()
        val t = start + LIVE_UPDATE_AFTER_MS
        live.plan(listOf(session()), t)
        live.dismiss("claude-code:1", turn)
        live.plan(listOf(session(status = SessionStatus.IDLE, turnStartedAt = null)), t + 2_000)
        // Same turnStartedAt again after a stop (e.g. a bridge without it resending): shown again.
        assertEquals(listOf(LiveChange.Post(update())), live.plan(listOf(session()), t + 4_000))
    }

    @Test
    fun forgottenUpdateIsPostedAgain() {
        val live = LiveUpdates()
        val t = start + LIVE_UPDATE_AFTER_MS
        live.plan(listOf(session()), t)
        live.forget("claude-code:1")
        assertEquals(listOf(LiveChange.Post(update())), live.plan(listOf(session()), t + 2_000))
    }

    @Test
    fun runningWithoutATaskListPostsEachNewMinute() {
        val live = LiveUpdates()
        val t = start + LIVE_UPDATE_AFTER_MS
        live.plan(listOf(session()), t)
        assertEquals(start + 2 * LIVE_UPDATE_AFTER_MS, live.nextDueAt(listOf(session()), t))
        // Within the minute: nothing new.
        assertEquals(emptyList<LiveChange>(), live.plan(listOf(session()), start + 2 * LIVE_UPDATE_AFTER_MS - 1))
        assertEquals(listOf(LiveChange.Post(update(minutes = 2))), live.plan(listOf(session()), start + 2 * LIVE_UPDATE_AFTER_MS))
        assertEquals(start + 3 * LIVE_UPDATE_AFTER_MS, live.nextDueAt(listOf(session()), start + 2 * LIVE_UPDATE_AFTER_MS))
        // A task list shows instead of the minutes: no more ticks, nor while waiting.
        live.plan(listOf(session(progress = TaskProgress(1, 3))), start + 2 * LIVE_UPDATE_AFTER_MS + 1_000)
        assertNull(live.nextDueAt(listOf(session(progress = TaskProgress(1, 3))), start + 2 * LIVE_UPDATE_AFTER_MS + 1_000))
        live.plan(listOf(session(status = SessionStatus.NEEDS_INPUT, turnStartedAt = null)), start + 2 * LIVE_UPDATE_AFTER_MS + 2_000)
        assertNull(live.nextDueAt(listOf(session(status = SessionStatus.NEEDS_INPUT, turnStartedAt = null)), start + 2 * LIVE_UPDATE_AFTER_MS + 2_000))
    }

    @Test
    fun chipShowsProgressElseMinutesWithinSevenCharacters() {
        assertEquals("▶ 3/7", liveUpdateChip(update(TaskProgress(3, 7))))
        assertEquals("▶ 12m", liveUpdateChip(update(minutes = 12)))
        assertEquals("▶ 9999m", liveUpdateChip(update(minutes = 9_999)))
        assertEquals("✋", liveUpdateChip(update(waiting = true)))
        // No turn start: the glyph alone.
        assertEquals("▶", liveUpdateChip(update(minutes = null)))
        // Too long with the glyph: the count alone; too long even so: the glyph.
        assertEquals("▶ 12/15", liveUpdateChip(update(TaskProgress(12, 15))))
        assertEquals("100/200", liveUpdateChip(update(TaskProgress(100, 200))))
        assertEquals("▶", liveUpdateChip(update(TaskProgress(1000, 2000))))
        val late = LiveUpdates().plan(listOf(session()), start + 100_000 * LIVE_UPDATE_AFTER_MS).single() as LiveChange.Post
        assertEquals("▶ 9999m", liveUpdateChip(late.update))
        for (chip in listOf(update(TaskProgress(12, 15)), update(minutes = 9_999), late.update).map(::liveUpdateChip)) {
            assertTrue(chip, chip.length <= LIVE_CHIP_MAX)
        }
    }

    @Test
    fun chipAndText() {
        assertEquals("▶ 1m", liveUpdateChip(update()))
        assertEquals("▶ running", liveUpdateText(update()))
        assertEquals("▶ 3/7", liveUpdateChip(update(TaskProgress(3, 7))))
        assertEquals("▶ 3/7", liveUpdateText(update(TaskProgress(3, 7))))
        assertEquals("✋", liveUpdateChip(update(TaskProgress(3, 7), waiting = true)))
        assertEquals("✋ waiting", liveUpdateText(update(TaskProgress(3, 7), waiting = true)))
        // Out of range from the bridge: clamped; an empty list is no list.
        assertEquals("▶ 7/7", liveUpdateChip(update(TaskProgress(9, 7))))
        assertEquals("▶", liveUpdateChip(update(TaskProgress(0, 0))))
    }

    @Test
    fun liveUpdateAppliesWhileRunningOrWaiting() {
        val id = notificationId("claude-code:1")
        fun applies(status: String) = Notifier.notificationApplies("live", Notifier.CHANNEL_LIVE, id, emptyList(), listOf(session(status = status)))
        assertTrue(applies(SessionStatus.RUNNING))
        assertTrue(applies(SessionStatus.NEEDS_INPUT))
        assertFalse(applies(SessionStatus.IDLE))
        assertFalse(Notifier.notificationApplies("live", Notifier.CHANNEL_LIVE, id, emptyList(), emptyList()))
    }
}
