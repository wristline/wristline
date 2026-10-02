package dev.wristline.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.SessionStatus
import dev.wristline.watch.data.TaskProgress
import dev.wristline.watch.data.isoToMillis

/** A turn running at least this long gets a Live Update; a short question and answer never does. */
internal const val LIVE_UPDATE_AFTER_MS = 60_000L

/** At most one round of Live Update changes per this interval. */
internal const val LIVE_UPDATE_INTERVAL_MS = 2_000L

/**
 * What one session's Live Update shows. [turn] is the session's turnStartedAt as sent (empty when
 * the bridge sent none), [startedAt] the same in millis, for the chronometer; [waiting] while the
 * session needs input.
 */
internal data class LiveUpdate(
    val sessionId: String,
    val turn: String,
    val startedAt: Long?,
    val title: String,
    val progress: TaskProgress?,
    val waiting: Boolean,
)

internal sealed interface LiveChange {
    data class Post(val update: LiveUpdate) : LiveChange

    data class Cancel(val sessionId: String) : LiveChange
}

/** A running turn worth a Live Update: [LIVE_UPDATE_AFTER_MS] old, or with a task list of two or more. */
internal fun liveUpdateDue(session: Session, now: Long): Boolean {
    if (session.status != SessionStatus.RUNNING) return false
    if ((session.progress?.total ?: 0) >= 2) return true
    val started = isoToMillis(session.turnStartedAt) ?: return false
    return now - started >= LIVE_UPDATE_AFTER_MS
}

/** [update]'s progress when it has a task list to show; null for an indeterminate one. */
internal fun liveProgress(update: LiveUpdate): TaskProgress? =
    update.progress?.takeIf { it.total > 0 }?.let { it.copy(done = it.done.coerceIn(0, it.total)) }

/** The status chip's text: `✋` while waiting, `3/7` with a task list, else `▶`. */
internal fun liveUpdateChip(update: LiveUpdate): String = when {
    update.waiting -> "✋"
    else -> liveProgress(update)?.let { "${it.done}/${it.total}" } ?: "▶"
}

/** The text under the title: `✋ waiting`, `▶ 3/7` or `▶ running`. */
internal fun liveUpdateText(update: LiveUpdate): String = when {
    update.waiting -> "✋ waiting"
    else -> "▶ " + (liveProgress(update)?.let { "${it.done}/${it.total}" } ?: "running")
}

/**
 * Which sessions have a Live Update ([LiveUpdate]), one each: a running session from when
 * [liveUpdateDue], kept while it runs and, marked waiting, while it needs input; cancelled when it
 * stops, ends or goes away. One the user dismissed ([dismiss]) stays away for the rest of its turn
 * (until a new turnStartedAt, or the session stops running).
 */
internal class LiveUpdates {
    private var shown: Map<String, LiveUpdate> = emptyMap()
    private val dismissed = HashMap<String, String>()

    /** The posts and cancels that bring the Live Updates in line with [sessions]; empty to cancel them all. */
    fun plan(sessions: List<Session>, now: Long): List<LiveChange> {
        dismissed.keys.retainAll { id -> sessions.any { it.id == id && it.status in TURN } }
        val wanted = LinkedHashMap<String, LiveUpdate>()
        for (session in sessions) wanted(session, now)?.let { wanted[session.id] = it }
        val changes = shown.keys.filter { it !in wanted }.map { LiveChange.Cancel(it) } +
            wanted.values.filter { shown[it.sessionId] != it }.map { LiveChange.Post(it) }
        shown = wanted
        return changes
    }

    private fun wanted(session: Session, now: Long): LiveUpdate? {
        val before = shown[session.id]
        return when (session.status) {
            SessionStatus.RUNNING -> {
                val turn = session.turnStartedAt.orEmpty()
                val keep = before != null && before.turn == turn
                if (dismissed[session.id] == turn || !(keep || liveUpdateDue(session, now))) return null
                LiveUpdate(session.id, turn, isoToMillis(session.turnStartedAt), session.title, session.progress, waiting = false)
            }
            // turnStartedAt is sent only while running: the waiting turn is the one shown.
            SessionStatus.NEEDS_INPUT -> before?.copy(title = session.title, progress = session.progress ?: before.progress, waiting = true)
            else -> null
        }
    }

    /** When a running session not shown yet becomes [liveUpdateDue] by age alone; null when none will. */
    fun nextDueAt(sessions: List<Session>, now: Long): Long? =
        sessions.filter { it.status == SessionStatus.RUNNING && it.id !in shown && dismissed[it.id] != it.turnStartedAt.orEmpty() }
            .mapNotNull { isoToMillis(it.turnStartedAt)?.plus(LIVE_UPDATE_AFTER_MS) }
            .filter { it > now }
            .minOrNull()

    /** The user swiped [sessionId]'s Live Update for [turn] away: it is not posted again for that turn. */
    fun dismiss(sessionId: String, turn: String) {
        dismissed[sessionId] = turn
        if (shown[sessionId]?.turn == turn) shown = shown - sessionId
    }

    /** [sessionId]'s Live Update could not be posted: the next [plan] tries again. */
    fun forget(sessionId: String) {
        shown = shown - sessionId
    }

    private companion object {
        /** The statuses a turn is still going in. */
        val TURN = setOf(SessionStatus.RUNNING, SessionStatus.NEEDS_INPUT)
    }
}

/** A Live Update swiped away ([Notifier.liveUpdate]'s delete intent); only the app's own PendingIntents reach it. */
class LiveUpdateDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        MonitorService.liveUpdates.dismiss(sessionId, intent.getStringExtra(EXTRA_TURN).orEmpty())
    }

    companion object {
        private const val EXTRA_SESSION_ID = "sessionId"
        private const val EXTRA_TURN = "turn"

        /** The data is unique per session, so the PendingIntents of different sessions stay apart. */
        fun intent(context: Context, sessionId: String, turn: String): Intent =
            Intent(context, LiveUpdateDismissReceiver::class.java)
                .setData(Uri.fromParts("wristline-live", sessionId, null))
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_TURN, turn)
    }
}
