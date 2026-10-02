package dev.wristline.watch

import dev.wristline.watch.data.Account
import dev.wristline.watch.data.AccountBook
import dev.wristline.watch.data.PendingRequest
import dev.wristline.watch.data.ProviderId
import dev.wristline.watch.data.RequestKind
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.SessionStatus
import dev.wristline.watch.data.edit
import dev.wristline.watch.data.seen
import dev.wristline.watch.ui.LimitEnd
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifierTest {
    // Pinned values: a notification posted by one process must be cancellable by the next, so the
    // id of a given request or session id may never change.
    @Test
    fun idsAreStableAcrossProcesses() {
        assertEquals(108400324, notificationId("req-3"))
        assertEquals(-761102199, notificationId("claude-code:6f1c2d3e-0000-4000-8000-000000000001"))
        assertEquals(-494130475, notificationId("codex:019a2b3c-4d5e-7f60-8a9b-0c1d2e3f4a5b"))
        assertEquals(1582880, notificationId("세션"))
    }

    @Test
    fun idsDependOnlyOnTheKey() {
        val key = "req-" + 42
        assertEquals(notificationId("req-42"), notificationId(key))
        assertNotEquals(notificationId("req-1"), notificationId("req-2"))
    }

    private fun session(status: String) = Session(id = "s1", provider = ProviderId.CLAUDE_CODE, status = status, lastActivity = "2026-09-30T00:00:00Z")

    private fun applies(tag: String?, channel: String, sessions: List<Session>, requests: List<PendingRequest> = emptyList()) =
        Notifier.notificationApplies(tag, channel, notificationId("s1"), requests, sessions)

    // Needs-input and done alerts share the "session" tag and id; the channel tells them apart.
    @Test
    fun needsInputAlertAppliesOnlyWhileTheSessionWaits() {
        val waiting = listOf(session(SessionStatus.NEEDS_INPUT))
        assertTrue(applies("session", Notifier.CHANNEL_REQUESTS, waiting))
        // Answered on the PC while the watch was offline: the snapshot shows the session idle.
        val idle = listOf(session(SessionStatus.IDLE))
        assertFalse(applies("session", Notifier.CHANNEL_REQUESTS, idle))
        for (status in listOf(SessionStatus.RUNNING, SessionStatus.ENDED)) {
            assertFalse(status, applies("session", Notifier.CHANNEL_REQUESTS, listOf(session(status))))
        }
        assertFalse(applies("session", Notifier.CHANNEL_REQUESTS, emptyList()))
    }

    @Test
    fun doneAlertAppliesUntilTheSessionWorksAgain() {
        assertTrue(applies("session", Notifier.CHANNEL_UPDATES, listOf(session(SessionStatus.IDLE))))
        assertTrue(applies("session", Notifier.CHANNEL_UPDATES, listOf(session(SessionStatus.NEEDS_INPUT))))
        assertFalse(applies("session", Notifier.CHANNEL_UPDATES, listOf(session(SessionStatus.RUNNING))))
        // Ended: the session has left the list.
        assertFalse(applies("session", Notifier.CHANNEL_UPDATES, emptyList()))
    }

    // A limit alert has its own tag: it stays until the session works again, whatever the channel.
    @Test
    fun limitAlertAppliesUntilTheSessionWorksAgain() {
        for (status in listOf(SessionStatus.IDLE, SessionStatus.NEEDS_INPUT)) {
            assertTrue(status, applies("limit", Notifier.CHANNEL_REQUESTS, listOf(session(status))))
        }
        assertFalse(applies("limit", Notifier.CHANNEL_REQUESTS, listOf(session(SessionStatus.RUNNING))))
        assertFalse(applies("limit", Notifier.CHANNEL_REQUESTS, emptyList()))
    }

    @Test
    fun limitTextIsTheSessionThenWhenTheLimitEnds() {
        val zone = ZoneId.of("Asia/Seoul")
        val now = ZonedDateTime.of(2026, 9, 29, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val evening = ZonedDateTime.of(2026, 9, 29, 19, 40, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("◷ 7:40 PM", Notifier.limitEndText(LimitEnd.At(evening, estimated = false), now, zone, is24Hour = false))
        assertEquals("◷ ~7:40 PM", Notifier.limitEndText(LimitEnd.At(evening, estimated = true), now, zone, is24Hour = false))
        assertEquals("¤ credits", Notifier.limitEndText(LimitEnd.Credits, now, zone, is24Hour = false))
        assertEquals("Fix CI · ◷ 7:40 PM", Notifier.limitText("Fix CI", "◷ 7:40 PM"))
        assertEquals("Fix CI", Notifier.limitText("Fix CI", null))
    }

    @Test
    fun requestNotificationsFollowPendingRequestsAndTheMonitoringOneStays() {
        val request = PendingRequest(id = "s1", sessionId = "x", kind = RequestKind.PERMISSION, createdAt = "2026-09-30T00:00:00Z")
        assertTrue(applies("request", Notifier.CHANNEL_REQUESTS, emptyList(), listOf(request)))
        assertFalse(applies("request", Notifier.CHANNEL_REQUESTS, emptyList()))
        assertTrue(applies(null, Notifier.CHANNEL_MONITOR, emptyList()))
    }

    @Test
    fun accountLineNamesTheAccountOnlyWhenItsProviderHasMore() {
        val work = Account("w", "Work", primary = true)
        val pro = Account("p", "Pro")
        fun codex(id: String, account: Account?) = Session(id, ProviderId.CODEX, status = SessionStatus.RUNNING, lastActivity = "2026-09-30T00:00:00Z", account = account)
        val sessions = listOf(codex("codex:1", work), codex("codex:2", pro))
        val book = AccountBook().seen(sessions.map { it.provider to it.account!! }, 0)
        assertEquals("Codex · Pro", accountLine("Codex", sessions[1], emptyList(), sessions, book))
        // The nickname in place of the label.
        val named = book.edit("codex:p") { it.copy(nickname = "Side") }
        assertEquals("Codex · Side", accountLine("Codex", sessions[1], emptyList(), sessions, named))
        // One account, a session without one, or none: no line.
        assertNull(accountLine("Codex", sessions[0], emptyList(), sessions.take(1), book))
        assertNull(accountLine("Codex", codex("codex:3", null), emptyList(), sessions, book))
        assertNull(accountLine("Codex", null, emptyList(), sessions, book))
    }
}
