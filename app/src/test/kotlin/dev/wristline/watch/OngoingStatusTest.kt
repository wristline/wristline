package dev.wristline.watch

import dev.wristline.watch.data.PendingRequest
import dev.wristline.watch.data.ProviderId
import dev.wristline.watch.data.RequestKind
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class OngoingStatusTest {
    private fun session(id: String, status: String) =
        Session(id, ProviderId.CLAUDE_CODE, status = status, lastActivity = "2026-09-29T12:00:00Z")

    private fun request(id: String) = PendingRequest(id, "claude-code:1", RequestKind.PERMISSION, createdAt = "2026-09-29T12:00:00Z")

    private fun text(sessions: List<Session>, requests: List<PendingRequest>, korean: Boolean = false) =
        if (korean) {
            ongoingStatusText(sessions, requests, { "실행 $it" }, { "대기 $it" }, "실행 중인 세션 없음")
        } else {
            ongoingStatusText(sessions, requests, { "$it running" }, { "$it waiting" }, "Nothing running")
        }

    private val sessions = listOf(
        session("a", SessionStatus.RUNNING),
        session("b", SessionStatus.RUNNING),
        session("c", SessionStatus.NEEDS_INPUT),
        session("d", SessionStatus.IDLE),
        session("e", SessionStatus.ENDED),
    )

    @Test
    fun countsRunningSessionsAndWaitingRequests() {
        assertEquals("2 running · 1 waiting", text(sessions, listOf(request("r1"))))
        assertEquals("실행 2 · 대기 1", text(sessions, listOf(request("r1")), korean = true))
    }

    @Test
    fun leavesOutZeroCounts() {
        assertEquals("2 running", text(sessions, emptyList()))
        assertEquals("3 waiting", text(listOf(session("d", SessionStatus.IDLE)), listOf(request("r1"), request("r2"), request("r3"))))
    }

    @Test
    fun idleWhenNothingRunsOrWaits() {
        assertEquals("Nothing running", text(emptyList(), emptyList()))
        assertEquals("Nothing running", text(listOf(session("d", SessionStatus.IDLE), session("e", SessionStatus.ENDED)), emptyList()))
    }
}
