package dev.wristline.watch.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertReplayTest {
    private val now = isoToMillis("2026-09-29T13:00:00Z")!!
    private val waiting = Session("claude-code:1", ProviderId.CLAUDE_CODE, status = SessionStatus.NEEDS_INPUT, lastActivity = "2026-09-29T12:58:00Z")
    private val idle = Session("codex:2", ProviderId.CODEX, status = SessionStatus.IDLE, lastActivity = "2026-09-29T12:57:00Z")
    private val running = Session("codex:3", ProviderId.CODEX, status = SessionStatus.RUNNING, lastActivity = "2026-09-29T12:59:00Z")
    private val sessions = listOf(waiting, idle, running)

    private fun alert(id: String?, sessionId: String, kind: String, at: String? = "2026-09-29T12:58:00Z") =
        ServerEvent.Alert(sessionId, kind, id = id, at = at)

    @Test
    fun keepsRecentAlertsThatStillApplyInOrder() {
        val alerts = listOf(
            alert("al-1", idle.id, AlertKind.DONE),
            alert("al-2", waiting.id, AlertKind.NEEDS_INPUT),
        )
        assertEquals(listOf("al-1", "al-2"), replayableAlerts(alerts, sessions, now).map { it.id })
    }

    @Test
    fun dropsOldOnesAndThoseWithoutAnId() {
        val alerts = listOf(
            alert("al-old", idle.id, AlertKind.DONE, at = "2026-09-29T12:49:59Z"),
            alert("al-edge", idle.id, AlertKind.DONE, at = "2026-09-29T12:50:00Z"),
            alert(null, idle.id, AlertKind.DONE),
            // Without a time the age is unknown, so it is kept.
            alert("al-untimed", idle.id, AlertKind.DONE, at = null),
        )
        assertEquals(listOf("al-edge", "al-untimed"), replayableAlerts(alerts, sessions, now).map { it.id })
    }

    @Test
    fun dropsAlertsTheSessionHasMovedPast() {
        val alerts = listOf(
            // Running again: its done alert is stale, as is a needs-input one.
            alert("al-1", running.id, AlertKind.DONE),
            alert("al-2", running.id, AlertKind.NEEDS_INPUT),
            // No longer waiting.
            alert("al-3", idle.id, AlertKind.NEEDS_INPUT),
            // Gone from the list.
            alert("al-4", "codex:9", AlertKind.DONE),
            alert("al-5", waiting.id, AlertKind.DONE),
        )
        assertEquals(listOf("al-5"), replayableAlerts(alerts, sessions, now).map { it.id })
    }

    @Test
    fun anAlertFilteredOutIsNotReplayedByALaterSnapshot() {
        val seen = ArrayDeque<String>()
        val handled = mutableListOf<String>()
        // What Bridge.onAlert does: each id once.
        val onAlert = { alert: ServerEvent.Alert -> if (seen.markSeen(alert.id!!)) handled += alert.id!! }
        val old = alert("al-1", running.id, AlertKind.DONE)
        // A reconnect while the session runs again: its done alert is stale.
        seen.replayAlerts(listOf(old), sessions, now, onAlert)
        // It finishes again; the newer alert comes live.
        val newer = alert("al-2", running.id, AlertKind.DONE, at = "2026-09-29T12:59:30Z")
        onAlert(newer)
        // Another reconnect within the replay window, the session idle now: the old one must not
        // replace the newer notification.
        seen.replayAlerts(listOf(old, newer), listOf(running.copy(status = SessionStatus.IDLE)), now, onAlert)
        assertEquals(listOf("al-2"), handled)
    }
}
