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
        assertEquals("▶ 2 · ✋ 2", text(sessions, listOf(request("r1"))))
        assertEquals("실행 2, 대기 2", description(sessions, listOf(request("r1"))))
    }

    @Test
    fun countsASessionWaitingOnSeveralRequestsOnce() {
        assertEquals("▶ 2 · ✋ 1", text(sessions, listOf(request("r1", "c"), request("r2", "c"))))
    }

    @Test
    fun showsBothCountsZerosIncluded() {
        assertEquals("▶ 2 · ✋ 0", text(sessions.filterNot { it.id == "c" }, emptyList()))
        assertEquals("실행 2", description(sessions.filterNot { it.id == "c" }, emptyList()))
        assertEquals("▶ 0 · ✋ 3", text(listOf(session("d", SessionStatus.IDLE)), listOf(request("r1", "x"), request("r2", "y"), request("r3", "z"))))
        assertEquals("대기 3", description(listOf(session("d", SessionStatus.IDLE)), listOf(request("r1", "x"), request("r2", "y"), request("r3", "z"))))
    }

    @Test
    fun zerosWhenNothingRunsOrWaits() {
        assertEquals("▶ 0 · ✋ 0", text(emptyList(), emptyList()))
        assertEquals("▶ 0 · ✋ 0", text(listOf(session("d", SessionStatus.IDLE), session("e", SessionStatus.ENDED)), emptyList()))
        assertEquals("실행 0, 대기 0", description(emptyList(), emptyList()))
    }

    @Test
    fun badgeShowsTheWaitingCountUpToNine() {
        assertNull(ongoingBadgeText(0))
        for (n in 1..9) assertEquals("$n", ongoingBadgeText(n))
        assertEquals("9+", ongoingBadgeText(10))
    }

    private fun content(conn: Conn, sessions: List<Session>, requests: List<PendingRequest> = emptyList(), worn: Boolean = true) =
        monitorContent(conn, ongoingCounts(sessions, requests), worn)

    @Test
    fun monitorRepostsOnlyForWhatItShows() {
        val shown = content(Conn.Online, sessions)
        assertEquals(MonitorContent("▶ 2 · ✋ 1", waiting = 1, running = 2), shown)
        // The same counts: the same card, nothing to post.
        assertEquals(shown, content(Conn.Online, sessions + session("f", SessionStatus.IDLE)))
        // Another running count: the text and the running badge change.
        assertEquals(MonitorContent("▶ 3 · ✋ 1", waiting = 1, running = 3), content(Conn.Online, sessions + session("f", SessionStatus.RUNNING)))
        // Another waiting count: text and badge change.
        assertEquals(MonitorContent("▶ 2 · ✋ 2", waiting = 2, running = 2), content(Conn.Online, sessions, listOf(request("r1"))))
        assertEquals(MonitorContent("▶ 2 · ✋ 0", running = 2), content(Conn.Online, sessions.filterNot { it.id == "c" }))
        // Idle and online: the plain icon.
        assertEquals(MonitorContent("▶ 0 · ✋ 0"), content(Conn.Online, emptyList()))
        // Not online or not worn: the last counts' text, grey and without badges.
        assertEquals(MonitorContent("▶ 2 · ✋ 1", offline = true), content(Conn.Connecting, sessions))
        assertEquals(MonitorContent("▶ 2 · ✋ 1", offline = true), content(Conn.Incompatible(2), sessions))
        assertEquals(MonitorContent("▶ 2 · ✋ 1", offline = true), content(Conn.Online, sessions, worn = false))
        // Nothing known yet: zeros.
        assertEquals(MonitorContent("▶ 0 · ✋ 0", offline = true), content(Conn.Connecting, emptyList()))
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

    @Test
    fun runningBadgeSitsAtTheBottomLeftAndCountsFromTwo() {
        assertNull(ongoingRunningBadge(96, 0))
        val one = ongoingRunningBadge(96, 1)!!
        // One running: a plain dot.
        assertEquals("", one.label)
        assertTrue(one.cx - one.radius >= 0 && one.cx + one.radius <= 48)
        assertTrue(one.cy - one.radius >= 48 && one.cy + one.radius <= 96)
        // The waiting badge's size, mirrored.
        val waiting = ongoingBadge(96, 1)!!
        assertEquals(waiting.radius, one.radius, 0f)
        assertEquals(96 - waiting.cx, one.cx, 0.01f)
        assertEquals(96 - waiting.cy, one.cy, 0.01f)
        assertEquals("2", ongoingRunningBadge(96, 2)!!.label)
        assertEquals("9+", ongoingRunningBadge(96, 10)!!.label)
    }

    @Test
    fun fillRisesFromTheSquirclesBottomToItsProgress() {
        assertNull(ongoingFill(96, 0f))
        assertNull(ongoingFill(96, -1f))
        val full = ongoingFill(96, 1f)!!
        // The squircle spans the icon edge to edge, but for its 1.12-unit margin of 512.
        assertEquals(96 - 96 * 1.12f / 512, full.bottom, 0.01f)
        assertEquals(96 * 1.12f / 512, full.top, 0.01f)
        val half = ongoingFill(96, 0.5f)!!
        assertEquals(full.bottom, half.bottom, 0f)
        assertEquals((full.bottom - full.top) / 2, half.bottom - half.top, 0.01f)
        assertEquals(48f, half.top, 0.01f)
        assertEquals(full, ongoingFill(96, 1.5f))
    }
}
