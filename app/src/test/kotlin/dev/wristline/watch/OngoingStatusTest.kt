package dev.wristline.watch

import dev.wristline.watch.data.Conn
import dev.wristline.watch.data.PendingRequest
import dev.wristline.watch.data.ProviderId
import dev.wristline.watch.data.RequestKind
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.SessionStatus
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun content(conn: Conn, sessions: List<Session>, requests: List<PendingRequest> = emptyList(), worn: Boolean = true) =
        monitorContent(conn, ongoingCounts(sessions, requests), worn, "Not worn") { if (it is Conn.Incompatible) "Update" else "Connecting" }

    @Test
    fun monitorRepostsOnlyForWhatItShows() {
        val shown = content(Conn.Online, sessions)
        assertEquals(MonitorContent("✋ 1", 1), shown)
        // More or fewer running sessions: the same card, nothing to post.
        assertEquals(shown, content(Conn.Online, sessions + session("f", SessionStatus.RUNNING)))
        assertEquals(shown, content(Conn.Online, sessions.filterNot { it.id == "a" }))
        // Another waiting count: text and badge change.
        assertEquals(MonitorContent("✋ 2", 2), content(Conn.Online, sessions, listOf(request("r1"))))
        assertEquals(MonitorContent(ONGOING_IDLE, 0), content(Conn.Online, sessions.filterNot { it.id == "c" }))
        // Not online or not worn: no badge, whatever waits.
        assertEquals(MonitorContent("Connecting", 0), content(Conn.Connecting, sessions))
        assertEquals(MonitorContent("Update", 0), content(Conn.Incompatible(2), sessions))
        assertEquals(MonitorContent("Not worn", 0), content(Conn.Online, sessions, worn = false))
    }

    @Test
    fun everyDropReadsAsOneConnectingState() {
        for (conn in listOf(Conn.Connecting, Conn.Offline, Conn.Unreachable(1), Conn.Unreachable(2))) assertEquals(Conn.Connecting, shownConn(conn))
        assertEquals(Conn.Online, shownConn(Conn.Online))
        assertEquals(Conn.Incompatible(2), shownConn(Conn.Incompatible(2)))
    }

    @Test
    fun aDropShorterThanTheGraceIsNotShown() = runBlocking {
        // Reconnected before the grace ran out: the drop never comes through.
        assertEquals(listOf(Conn.Online, Conn.Online), settledConn(flowOf(Conn.Online, Conn.Offline, Conn.Unreachable(1), Conn.Online)).toList())
        assertEquals(listOf(Conn.Incompatible(2)), settledConn(flowOf(Conn.Connecting, Conn.Incompatible(2))).toList())
    }

    @Test
    fun badgeSitsAtTheTopRightInsideTheIcon() {
        assertNull(ongoingBadge(96, 0))
        val one = ongoingBadge(96, 3)!!
        assertEquals("3", one.label)
        assertTrue(one.cx - one.radius >= 48 && one.cx + one.radius <= 96)
        assertTrue(one.cy - one.radius >= 0 && one.cy + one.radius <= 48)
        // Two characters get a smaller text in the same disc.
        val many = ongoingBadge(96, 12)!!
        assertEquals("9+", many.label)
        assertEquals(one.radius, many.radius, 0f)
        assertTrue(many.textSize < one.textSize)
    }
}
