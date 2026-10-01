package dev.wristline.watch.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import dev.wristline.watch.R
import dev.wristline.watch.data.Bridge
import dev.wristline.watch.data.Conn
import dev.wristline.watch.data.PendingRequest
import dev.wristline.watch.data.ProviderId
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.Usage
import dev.wristline.watch.data.UsageWindow
import dev.wristline.watch.data.isoToMillis
import kotlin.math.roundToInt

/** The icon buttons at the top of the list. */
private val ACTION_SIZE = 40.dp
private val ACTION_GAP = 8.dp

/** The provider badge beside labelSmall text (the limit card, a session card's second line). */
private val SMALL_BADGE = 14.dp

/** The limit card's glyphs (reset, sessions), the gap after one, and the gaps between groups and lines. */
private val LIMIT_GLYPH = 12.dp
private val LIMIT_GLYPH_GAP = 3.dp
private val LIMIT_GROUP_GAP = 8.dp
private val LIMIT_LINE_GAP = 2.dp

/** A limit line's groups: the badge and percentage, the reset countdown, the session count. */
private const val LIMIT_CELLS = 3

private const val MINUTES_PER_DAY = 1_440

@Composable
internal fun SessionListScreen(
    onSession: (String) -> Unit,
    onRequest: (String) -> Unit,
    onUsage: () -> Unit,
    onAsk: (String) -> Unit,
    onAskHistory: () -> Unit,
    onSettings: () -> Unit,
    onRepair: () -> Unit,
) {
    val conn by Bridge.conn.collectAsStateWithLifecycle()
    val sessions by Bridge.sessions.collectAsStateWithLifecycle()
    val requests by Bridge.requests.collectAsStateWithLifecycle()
    val usage by Bridge.usage.collectAsStateWithLifecycle()
    val ask = rememberQuickAsk(onStarted = onAsk)
    // Read by the cards' time only, so the minute tick recomposes just the visible cards' time.
    val now = rememberNowState()
    SessionListContent(
        conn = conn,
        sessions = sessions,
        requests = requests,
        usage = usage,
        now = { now.value },
        onSession = onSession,
        onRequest = onRequest,
        onUsage = onUsage,
        onAsk = ask,
        onAskHistory = onAskHistory,
        onSettings = onSettings,
        onRetry = Bridge::retryNow,
        onRepair = onRepair,
    )
}

@Composable
internal fun SessionListContent(
    conn: Conn,
    sessions: List<Session>,
    requests: List<PendingRequest>,
    usage: List<Usage>,
    now: () -> Long,
    onSession: (String) -> Unit,
    onRequest: (String) -> Unit,
    onUsage: () -> Unit,
    onAsk: () -> Unit,
    onAskHistory: () -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    onRepair: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    // Stale data stays readable but visibly out of date.
    val stale = conn is Conn.Offline || conn is Conn.Unreachable || conn is Conn.Unauthorized
    val oldestRequest = remember(requests) { requests.minByOrNull { it.createdAt } }
    val limits = remember(usage, sessions) { limitLines(usage, sessions) }
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            flingBehavior = TransformingLazyColumnDefaults.snapFlingBehavior(listState),
            rotaryScrollableBehavior = RotaryScrollableDefaults.snapBehavior(listState),
        ) {
            // In place of a title: Settings, Quick Ask (the primary action) and the recent questions.
            item(key = "actions") {
                Row(
                    Modifier.fillMaxWidth().edgeTransform(this, spec),
                    horizontalArrangement = Arrangement.spacedBy(ACTION_GAP, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val iconSize = IconButtonDefaults.iconSizeFor(ACTION_SIZE)
                    // Round, squarer while pressed.
                    val shapes = IconButtonDefaults.animatedShapes()
                    FilledTonalIconButton(onClick = onSettings, modifier = Modifier.size(ACTION_SIZE), shapes = shapes) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings_title), Modifier.size(iconSize))
                    }
                    FilledIconButton(onClick = onAsk, modifier = Modifier.size(ACTION_SIZE), shapes = shapes) {
                        Icon(painterResource(R.drawable.ic_mic), stringResource(R.string.ask_button), Modifier.size(iconSize))
                    }
                    FilledTonalIconButton(onClick = onAskHistory, modifier = Modifier.size(ACTION_SIZE), shapes = shapes) {
                        Icon(painterResource(R.drawable.ic_history), stringResource(R.string.ask_history_title), Modifier.size(iconSize))
                    }
                }
            }
            if (oldestRequest != null) {
                item(key = "requests") {
                    val colors = MaterialTheme.colorScheme
                    Button(
                        onClick = { onRequest(oldestRequest.id) },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).animateItemCalmly(this),
                        transformation = SurfaceTransformation(spec),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.tertiary, contentColor = colors.onTertiary),
                        label = {
                            Text(pluralStringResource(R.plurals.requests_waiting, requests.size, requests.size))
                        },
                        secondaryLabel = {
                            val session = sessions.firstOrNull { it.id == oldestRequest.sessionId }
                            Text(session?.let { sessionTitle(it) } ?: oldestRequest.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                    )
                }
            }
            if (conn.hasBanner()) {
                item(key = "conn", contentType = "conn") { ConnBanner(conn, spec, onRetry, onRepair) }
            }
            if (limits.isNotEmpty()) {
                item(key = "usage") {
                    val interaction = remember { MutableInteractionSource() }
                    val depth = rememberPressDepth(interaction)
                    Card(
                        onClick = onUsage,
                        // The card's own minimum height (64dp) applies only where none is set:
                        // with this one the card is as tall as its lines.
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, spec)
                            .animateItemCalmly(this)
                            .pressScale(depth)
                            .heightIn(min = 1.dp),
                        transformation = SurfaceTransformation(spec),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        interactionSource = interaction,
                    ) {
                        LimitTable(limits, now, Modifier.align(Alignment.CenterHorizontally))
                    }
                }
            }
            // Only an up-to-date list can say there are none; otherwise the banner explains.
            if (sessions.isEmpty() && (conn is Conn.Online || conn is Conn.Demo)) {
                item(key = "empty") {
                    BodyText(stringResource(R.string.sessions_empty), Modifier.edgeTransform(this, spec))
                }
            }
            items(sessions, key = { it.id }, contentType = { "session" }) { session ->
                SessionCard(
                    session = session,
                    stale = stale,
                    now = now,
                    spec = spec,
                    onClick = { onSession(session.id) },
                )
            }
        }
    }
}

@Composable
internal fun sessionTitle(session: Session): String =
    session.title.ifBlank { basename(session.cwd) }.ifBlank { stringResource(R.string.session_untitled) }

/** A limit window's percentage from here up is shown in yellow (see [limitColor]). */
internal const val NEAR_LIMIT_PERCENT = 80

/** A limit window's percentage from here up is shown in red (see [limitColor]). */
internal const val AT_LIMIT_PERCENT = 95

/** A provider's line on the limit card (see [limitLines]). */
internal data class LimitLine(
    val provider: String,
    /** The used percentage of its [shortWindow], rounded; null without usage. */
    val percent: Int?,
    /** When that window resets, in epoch milliseconds; null when unknown. */
    val resetsAt: Long?,
    /** The provider's sessions in the list, which has live ones only. */
    val sessions: Int,
)

/**
 * The limit card's lines: one per provider with usage or sessions, in provider order. Of a
 * provider's accounts the one closest to its short window's limit gives the numbers (the Usage
 * screen has the rest), compared among the windows of the provider's shortest length only; the
 * session count is the provider's total. Entries without a [shortWindow] count as no usage.
 */
internal fun limitLines(usage: List<Usage>, sessions: List<Session>): List<LimitLine> {
    val windows = usage
        .mapNotNull { u -> shortWindow(u)?.let { u.provider to it } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, windows) ->
            val shortest = windows.minOf(::windowLength)
            windows.filter { windowLength(it) == shortest }.maxBy { it.usedPercent }
        }
    val counts = sessions.groupingBy { it.provider }.eachCount()
    return (windows.keys + counts.keys).sorted().map { provider ->
        val window = windows[provider]
        LimitLine(provider, window?.usedPercent?.roundToInt(), isoToMillis(window?.resetsAt), counts[provider] ?: 0)
    }
}

/**
 * The window a limit line shows: Claude Code's 5-hour window (`5h`), as on a session's gauge
 * ([sessionLimit]), else the shortest (Codex's primary, which may be weekly; of equals the first).
 * Null for a Claude Code entry without one, as when it has reset and no request since has reported
 * the next: the weekly number in its place would read as the 5-hour one.
 */
internal fun shortWindow(usage: Usage): UsageWindow? =
    if (usage.provider == ProviderId.CLAUDE_CODE) {
        usage.windows.firstOrNull { it.id == "5h" }
    } else {
        usage.windows.minByOrNull(::windowLength)
    }

/** A window's length to compare by: [windowMinutes], a window of unknown length the longest. */
private fun windowLength(window: UsageWindow): Int = windowMinutes(window)?.takeIf { it > 0 } ?: Int.MAX_VALUE

/**
 * [line] as of [now]: past its reset time the window is over and its percentage old, so the line
 * reads as without usage until the bridge reports again (it drops such windows then).
 */
internal fun currentLine(line: LimitLine, now: Long): LimitLine =
    if (line.resetsAt != null && line.resetsAt <= now) line.copy(percent = null, resetsAt = null) else line

/** Whole minutes from [now] until [resetsAt]; 0 once it has passed. */
internal fun minutesLeft(resetsAt: Long, now: Long): Long = ((resetsAt - now) / 60_000).coerceAtLeast(0)

/**
 * The countdown to a reset in digits, the same in every language: `2:13` under a day, else whole
 * days in [dayPattern] (`%1$dd`, `%1$d일`).
 */
internal fun remainingText(minutes: Long, dayPattern: String): String =
    if (minutes >= MINUTES_PER_DAY) {
        dayPattern.format(minutes / MINUTES_PER_DAY)
    } else {
        "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"
    }

/**
 * The limit card's [lines] as a table: each group starts at the same place on every line, the
 * widest of a column setting its width; a column no line uses (no reset times) takes no room.
 */
@Composable
private fun LimitTable(lines: List<LimitLine>, now: () -> Long, modifier: Modifier = Modifier) {
    Layout(content = { lines.forEach { LimitCells(it, now) } }, modifier = modifier) { measurables, constraints ->
        val rows = measurables.map { it.measure(Constraints()) }.chunked(LIMIT_CELLS)
        val groupGap = LIMIT_GROUP_GAP.roundToPx()
        val lineGap = LIMIT_LINE_GAP.roundToPx()
        val widths = List(LIMIT_CELLS) { column -> rows.maxOf { it[column].width } }
        val starts = widths.runningFold(0) { x, width -> if (width > 0) x + width + groupGap else x }
        val heights = rows.map { row -> row.maxOf { it.height } }
        val width = widths.indices.maxOf { starts[it] + widths[it] }
        val height = heights.sum() + lineGap * (rows.size - 1)
        layout(constraints.constrainWidth(width), constraints.constrainHeight(height)) {
            var y = 0
            rows.forEachIndexed { i, row ->
                row.forEachIndexed { column, cell -> cell.placeRelative(starts[column], y + (heights[i] - cell.height) / 2) }
                y += heights[i] + lineGap
            }
        }
    }
}

/**
 * A line's [LIMIT_CELLS] groups, icons and numbers only: `[C] 42%`, `⧗ 2:13`, `▣ 3`. Without usage
 * (or past the reset time, see [currentLine]) the percentage is a dash and, as without a reset
 * time, the countdown group is empty. Read out as one sentence. Reads [now] itself: the minute tick
 * recomposes the lines, not the list.
 */
@Composable
private fun LimitCells(limit: LimitLine, now: () -> Long) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
    val at = now()
    val line = currentLine(limit, at)
    val left = line.resetsAt?.let { minutesLeft(it, at) }
    val description = limitDescription(line, left)
    Row(
        Modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderBadge(line.provider, size = SMALL_BADGE)
        val percent = line.percent
        if (percent != null) {
            Text("$percent%", color = limitColor(percent), style = style, maxLines = 1)
        } else {
            Text("—", color = colors.onSurfaceVariant, style = style, maxLines = 1)
        }
    }
    Row(Modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(LIMIT_GLYPH_GAP), verticalAlignment = Alignment.CenterVertically) {
        if (left != null) {
            Icon(painterResource(R.drawable.ic_hourglass), null, Modifier.size(LIMIT_GLYPH), tint = colors.onSurfaceVariant)
            Text(remainingText(left, stringResource(R.string.limit_days)), color = colors.onSurface, style = style, maxLines = 1)
        }
    }
    Row(Modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(LIMIT_GLYPH_GAP), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_terminal), null, Modifier.size(LIMIT_GLYPH), tint = colors.onSurfaceVariant)
        Text("${line.sessions}", color = colors.onSurface, style = style, maxLines = 1)
    }
}

/** `Claude Code: 42% used, resets in 2 hours 13 minutes, 3 active sessions`, [left] in minutes. */
@Composable
private fun limitDescription(line: LimitLine, left: Long?): String {
    val name = providerLabel(line.provider)
    val parts = buildList {
        add(line.percent?.let { stringResource(R.string.limit_used, name, it) } ?: stringResource(R.string.limit_unknown, name))
        if (left != null) add(stringResource(R.string.limit_resets_in, durationWords(left)))
        add(pluralStringResource(R.plurals.limit_sessions, line.sessions, line.sessions))
    }
    return parts.joinToString(", ")
}

/** [minutes] in words: `3 days` from a day, else `2 hours 13 minutes`, `2 hours`, `13 minutes`. */
@Composable
private fun durationWords(minutes: Long): String {
    val days = (minutes / MINUTES_PER_DAY).toInt()
    val hours = (minutes / 60).toInt()
    val rest = (minutes % 60).toInt()
    return when {
        days > 0 -> pluralStringResource(R.plurals.duration_days, days, days)
        hours > 0 && rest > 0 ->
            pluralStringResource(R.plurals.duration_hours, hours, hours) + " " + pluralStringResource(R.plurals.duration_minutes, rest, rest)
        hours > 0 -> pluralStringResource(R.plurals.duration_hours, hours, hours)
        else -> pluralStringResource(R.plurals.duration_minutes, rest, rest)
    }
}

// Builds its modifier and transformation itself, as ItemRow does: made by the caller they would be
// new on every recomposition of the list, and the card could never skip.
@Composable
private fun TransformingLazyColumnItemScope.SessionCard(
    session: Session,
    stale: Boolean,
    now: () -> Long,
    spec: TransformationSpec,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val depth = rememberPressDepth(interaction)
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, spec)
            .animateItemCalmly(this)
            .pressScale(depth)
            .then(if (stale) Modifier.alpha(0.6f) else Modifier),
        transformation = SurfaceTransformation(spec),
        // A card's content is gray by default; the name is white, the line under it gray.
        colors = CardDefaults.cardColors(contentColor = MaterialTheme.colorScheme.onSurface),
        interactionSource = interaction,
    ) {
        // Line 1: the status dot and the session's name, which tells cards apart: the largest text.
        // The dot sits on the top line: near the screen's bottom edge the list morphs the card's
        // lower corners, which would hide it at the start of the second line.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(session.status)
            Text(sessionTitle(session), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Line 2, muted: `12 min. ago · [C] repo`; accounts are on the Usage screen.
        // Recomputed once per tick and activity, not on every recomposition of the card.
        val at = now()
        val ago = remember(session.lastActivity, at) {
            if (isJustNow(session.lastActivity, at)) null else relativeTime(session.lastActivity, at)
        }
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Row(
            Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text((ago ?: stringResource(R.string.time_just_now)) + " ·", color = muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            ProviderBadge(session.provider, size = SMALL_BADGE)
            Text(basename(session.cwd), color = muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
