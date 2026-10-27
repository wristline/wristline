package dev.wristline.watch.data

import dev.wristline.watch.ui.bindingWindow
import kotlin.math.abs
import kotlin.math.roundToInt

/** A limit window warns from this much use, short of the bridge's own limit alert at 100. */
internal const val WARN_LIMIT_PERCENT = 80.0

/** A session's context warns from this much use, and again only after it fell below [WARN_CONTEXT_REARM_PERCENT] (compacted). */
internal const val WARN_CONTEXT_PERCENT = 85.0
internal const val WARN_CONTEXT_REARM_PERCENT = 70.0

/** Reset times this close are one window: the bridge's merge tolerance, as Codex jitters them by about a second. */
private const val SAME_WINDOW_MS = 5 * 60_000L

/** The keys [Warnings] keeps at most, the oldest dropped first. */
private const val MAX_FIRED = 32

private const val LIMIT = "limit"
private const val CONTEXT = "context"
private const val SEPARATOR = '\t'

/** An early warning to give ([Warnings]). */
internal sealed interface Warning {
    /** [usage]'s binding [window] reached [WARN_LIMIT_PERCENT]. */
    data class Limit(val usage: Usage, val window: UsageWindow) : Warning

    /** [session]'s context reached [WARN_CONTEXT_PERCENT]: [percent] rounded. */
    data class Context(val session: Session, val percent: Int) : Warning
}

/**
 * Which early warnings are due, each once. A limit: per usage entry, its [bindingWindow] (as the
 * card picks it) from [WARN_LIMIT_PERCENT] up to but not at 100 (the bridge alerts then), once per
 * provider, account, window and reset time; it warns again in the next cycle, or once its use fell
 * back below the threshold (a window without a reset time). A session's context: from
 * [WARN_CONTEXT_PERCENT], again only after it fell below [WARN_CONTEXT_REARM_PERCENT] or the
 * session went away; an ended session is not checked.
 *
 * [fired] is what has warned, as plain keys the caller keeps across restarts (Prefs); dropped when
 * the window's reset time passed or the session is gone, and capped. Not thread-safe.
 */
internal class Warnings(fired: Set<String> = emptySet()) {
    private val keys = LinkedHashSet(fired)

    val fired: Set<String> get() = keys.toSet()

    /** The warnings due for [usage] and [sessions] as of [now]; none while not [enabled], when nothing changes. */
    fun check(usage: List<Usage>, sessions: List<Session>, now: Long, enabled: Boolean = true): List<Warning> {
        if (!enabled) return emptyList()
        val live = sessions.filter { it.status != SessionStatus.ENDED }
        val liveIds = live.mapTo(HashSet()) { it.id }
        keys.removeAll { key ->
            val parts = key.split(SEPARATOR)
            when (parts[0]) {
                LIMIT -> (parts.getOrNull(4)?.toLongOrNull()?.let { it <= now } ?: false) || reportedBelow(parts, usage)
                CONTEXT -> parts.getOrNull(1) !in liveIds
                else -> true
            }
        }
        val warnings = ArrayList<Warning>()
        for (entry in usage) {
            val window = bindingWindow(entry.windows, now) ?: continue
            if (window.usedPercent < WARN_LIMIT_PERCENT || window.usedPercent >= 100) continue
            val resetsAt = isoToMillis(window.resetsAt)
            if (keys.any { sameWindow(it, entry, window.id, resetsAt) }) continue
            keys += listOf(LIMIT, entry.provider, entry.account?.id.orEmpty(), window.id, resetsAt?.toString().orEmpty()).joinToString(SEPARATOR.toString())
            warnings += Warning.Limit(entry, window)
        }
        for (session in live) {
            val context = session.context?.takeIf { it.window > 0 } ?: continue
            val percent = context.used * 100.0 / context.window
            val key = CONTEXT + SEPARATOR + session.id
            when {
                percent < WARN_CONTEXT_REARM_PERCENT -> keys -= key
                percent >= WARN_CONTEXT_PERCENT && keys.add(key) -> warnings += Warning.Context(session, percent.roundToInt())
            }
        }
        while (keys.size > MAX_FIRED) keys.remove(keys.first())
        return warnings
    }

    /** The limit key [parts]' window is reported below [WARN_LIMIT_PERCENT]: it reset (one without a reset time tells only so). */
    private fun reportedBelow(parts: List<String>, usage: List<Usage>): Boolean =
        usage.any { entry ->
            entry.provider == parts[1] && entry.account?.id.orEmpty() == parts[2] &&
                entry.windows.any { it.id == parts[3] && it.usedPercent < WARN_LIMIT_PERCENT }
        }

    private fun sameWindow(key: String, entry: Usage, windowId: String, resetsAt: Long?): Boolean {
        val parts = key.split(SEPARATOR)
        if (parts.size != 5 || parts[0] != LIMIT || parts[1] != entry.provider || parts[2] != entry.account?.id.orEmpty() || parts[3] != windowId) return false
        val fired = parts[4].toLongOrNull()
        return if (fired == null || resetsAt == null) fired == resetsAt else abs(fired - resetsAt) <= SAME_WINDOW_MS
    }
}
