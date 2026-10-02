package dev.wristline.watch

import dev.wristline.watch.data.PendingRequest
import dev.wristline.watch.data.ProviderId
import dev.wristline.watch.data.RequestKind
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OngoingStatusTest {
    private fun session(id: String, status: String) =
        Session(id, ProviderId.CLAUDE_CODE, status = status, lastActivity = "2026-09-29T12:00:00Z")

    private fun request(id: String, sessionId: String = "claude-code:1") =
        PendingRequest(id, sessionId, RequestKind.PERMISSION, createdAt = "2026-09-29T12:00:00Z")

    private fun text(sessions: List<Session>, requests: List<PendingRequest>) = ongoingStatusText(ongoingCounts(sessions, requests))

    private fun description(sessions: List<Session>, requests: List<PendingRequest>) =
        ongoingStatusDescription(ongoingCounts(sessions, requests), { "실행 $it" }, { "대기 $it" })

    private val sessions = listOf(
        session("a", SessionStatus.RUNNING),
        session("b", SessionStatus.RUNNING),
        session("c", SessionStatus.NEEDS_INPUT),
        session("d", SessionStatus.IDLE),
        session("e", SessionStatus.ENDED),
    )

    @Test
    fun countsRunningAndWaitingSessions() {
        // c needs input; the request waits on another session.
        assertEquals("✋ 2", text(sessions, listOf(request("r1"))))
        assertEquals("실행 2, 대기 2", description(sessions, listOf(request("r1"))))
    }

    @Test
    fun countsASessionWaitingOnSeveralRequestsOnce() {
        assertEquals("✋ 1", text(sessions, listOf(request("r1", "c"), request("r2", "c"))))
    }

    @Test
    fun showsOnlyTheWaitingCount() {
        assertEquals(ONGOING_IDLE, text(sessions.filterNot { it.id == "c" }, emptyList()))
        assertEquals("실행 2", description(sessions.filterNot { it.id == "c" }, emptyList()))
        assertEquals("✋ 3", text(listOf(session("d", SessionStatus.IDLE)), listOf(request("r1", "x"), request("r2", "y"), request("r3", "z"))))
        assertEquals("대기 3", description(listOf(session("d", SessionStatus.IDLE)), listOf(request("r1", "x"), request("r2", "y"), request("r3", "z"))))
    }

    @Test
    fun idleWhenNothingWaits() {
        assertEquals(ONGOING_IDLE, text(emptyList(), emptyList()))
        assertEquals(ONGOING_IDLE, text(listOf(session("d", SessionStatus.IDLE), session("e", SessionStatus.ENDED)), emptyList()))
        assertEquals("실행 0, 대기 0", description(emptyList(), emptyList()))
    }

    @Test
    fun badgeShowsTheWaitingCountUpToNine() {
        assertNull(ongoingBadgeText(0))
        for (n in 1..9) assertEquals("$n", ongoingBadgeText(n))
        assertEquals("9+", ongoingBadgeText(10))
    }
}
