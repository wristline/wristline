package dev.wristline.watch.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
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
import java.time.ZoneId
import kotlin.math.roundToInt

/** The icon buttons at the top of the list. */
private val ACTION_SIZE = 40.dp
private val ACTION_GAP = 8.dp

/** The provider badge beside labelSmall text (the limit card, a session card's second line). */
private val SMALL_BADGE = 14.dp

/** The limit card's glyphs (reset, sessions; the Usage screen's too), the gap after one, and the gaps between groups and lines. */
internal val LIMIT_GLYPH = 12.dp
internal val LIMIT_GLYPH_GAP = 3.dp
private val LIMIT_GROUP_GAP = 6.dp
private val LIMIT_LINE_GAP = 2.dp

/** The account mark on a [SMALL_BADGE] badge (see [BadgeWithMark]), and the ring around it in the card's color. */
private val MARK = 8.dp
private val MARK_RING = 1.dp

/**
 * A limit line's cells: the badge and percentage, the reset clock in full and compact (one of the
 * two is placed, see [LimitTable]), the session count.
 */
private const val LIMIT_CELLS = 4
private const val CELL_CLOCK = 1
private const val CELL_CLOCK_COMPACT = 2

internal const val MINUTES_PER_DAY = 1_440

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
                        // 6dp at the sides: at 226dp the widest line (100%, Wed 11:59p, 12) fits with room to spare.
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
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

/** A line on the limit card: a provider's account, or a provider with sessions but no usage (see [limitLines]). */
internal data class LimitLine(
    val provider: String,
    /** The used percentage of its [shortWindow], rounded; null without usage. */
    val percent: Int?,
    /** When that window resets, in epoch milliseconds; null when unknown. */
    val resetsAt: Long?,
    /** Its sessions in the list, which has live ones only. */
    val sessions: Int,
    /** The account's label when its provider has more than one line (see [accountMark]); else null. */
    val account: String? = null,
)

/**
 * The limit card's lines: one per usage entry with windows (the bridge sends the accounts logged in
 * now only), in [usageOrder], and one for a provider with sessions but no usage. A line's numbers
 * are its entry's [shortWindow]; without one it reads as without usage. It counts the provider's
 * sessions of its account; the first line of a provider also counts those without an account or of
 * an account without a line. When a provider has more than one line, each names its account.
 */
internal fun limitLines(usage: List<Usage>, sessions: List<Session>): List<LimitLine> {
    val entries = usageOrder(usage.filter { it.windows.isNotEmpty() }).groupBy { it.provider }
    val live = sessions.groupBy { it.provider }
    return (entries.keys + live.keys).sorted().flatMap { provider ->
        val own = live[provider].orEmpty()
        val accounts = entries[provider] ?: return@flatMap listOf(LimitLine(provider, null, null, own.size))
        val counts = own.groupingBy { session ->
            accounts.indexOfFirst { it.account != null && it.account.id == session.account?.id }.coerceAtLeast(0)
        }.eachCount()
        accounts.mapIndexed { i, entry ->
            val window = shortWindow(entry)
            val account = entry.account?.label?.takeIf { accounts.size > 1 }
            LimitLine(provider, window?.usedPercent?.roundToInt(), isoToMillis(window?.resetsAt), counts[i] ?: 0, account)
        }
    }
}

/** The mark of an account on its provider's badge: its label's first character, as given. */
internal fun accountMark(label: String): String {
    val trimmed = label.trim()
    return if (trimmed.isEmpty()) "" else trimmed.substring(0, trimmed.offsetByCodePoints(0, 1))
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
    if (resetPassed(line.resetsAt, now)) line.copy(percent = null, resetsAt = null) else line

/** Whether a window resetting at [resetsAt] is over by [now]: from its reset time on; never without one. */
internal fun resetPassed(resetsAt: Long?, now: Long): Boolean = resetsAt != null && resetsAt <= now

/** Whole minutes from [now] until [resetsAt]; 0 once it has passed. */
internal fun minutesLeft(resetsAt: Long, now: Long): Long = ((resetsAt - now) / 60_000).coerceAtLeast(0)

/**
 * The limit card's [lines] as a table: each group starts at the same place on every line, the
 * widest of a column setting its width; a column no line uses (no reset times) takes no room. The
 * reset clocks are the full ones unless those make the table wider than the card, then the compact.
 */
@Composable
private fun LimitTable(lines: List<LimitLine>, now: () -> Long, modifier: Modifier = Modifier) {
    Layout(content = { lines.forEach { LimitCells(it, now) } }, modifier = modifier) { measurables, constraints ->
        val rows = measurables.map { it.measure(Constraints()) }.chunked(LIMIT_CELLS)
        val groupGap = LIMIT_GROUP_GAP.roundToPx()
        val lineGap = LIMIT_LINE_GAP.roundToPx()
        fun widths(columns: List<Int>) = columns.map { column -> rows.maxOf { it[column].width } }
        fun starts(widths: List<Int>) = widths.runningFold(0) { x, width -> if (width > 0) x + width + groupGap else x }
        fun width(widths: List<Int>) = starts(widths).let { starts -> widths.indices.maxOf { starts[it] + widths[it] } }
        val full = listOf(0, CELL_CLOCK, LIMIT_CELLS - 1)
        val columns = if (width(widths(full)) <= constraints.maxWidth) full else listOf(0, CELL_CLOCK_COMPACT, LIMIT_CELLS - 1)
        val widths = widths(columns)
        val starts = starts(widths)
        val heights = rows.map { row -> columns.maxOf { row[it].height } }
        val height = heights.sum() + lineGap * (rows.size - 1)
        layout(constraints.constrainWidth(width(widths)), constraints.constrainHeight(height)) {
            var y = 0
            rows.forEachIndexed { i, row ->
                columns.forEachIndexed { column, cell -> row[cell].placeRelative(starts[column], y + (heights[i] - row[cell].height) / 2) }
                y += heights[i] + lineGap
            }
        }
    }
}

/** A line's reset clock: [full] and [compact] on screen ([resetClockText]), [words] for TalkBack ([resetClockWords]). */
private data class ResetClock(val full: String, val compact: String, val words: String)

/**
 * A line's [LIMIT_CELLS] cells, icons and numbers only: `[C] 42%`, `◷ 16:40` (`◷ Fri 4:40 PM`, or
 * compact `◷ Fri 4:40p`), `▣ 3`, the badge marked with the account when its provider has more lines.
 * Without usage (or past the reset time, see [currentLine]) the percentage is a dash and, as without
 * a reset time, the clock cells are empty. Read out as one sentence. Reads [now] only to see the
 * reset pass and the clock text change (at midnight): the minute tick recomposes nothing else.
 */
@Composable
private fun LimitCells(limit: LimitLine, now: () -> Long) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
    val line by remember(limit, now) { derivedStateOf { currentLine(limit, now()) } }
    val resetsAt = line.resetsAt
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val locale = LocalConfiguration.current.locales[0]
    val today = stringResource(R.string.limit_today)
    val clock by remember(resetsAt, now, is24Hour, locale, today) {
        derivedStateOf {
            resetsAt?.let {
                val at = now()
                val zone = ZoneId.systemDefault()
                ResetClock(
                    resetClockText(it, at, zone, is24Hour),
                    resetClockText(it, at, zone, is24Hour, compact = true),
                    resetClockWords(it, at, zone, locale, is24Hour, today),
                )
            }
        }
    }
    val description = limitDescription(line, clock?.words)
    Row(
        Modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BadgeWithMark(line.provider, line.account?.let(::accountMark))
        val percent = line.percent
        if (percent != null) {
            Text("$percent%", color = limitColor(percent), style = style, maxLines = 1)
        } else {
            Text("—", color = colors.onSurfaceVariant, style = style, maxLines = 1)
        }
    }
    for (text in listOf(clock?.full, clock?.compact)) {
        Row(Modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(LIMIT_GLYPH_GAP), verticalAlignment = Alignment.CenterVertically) {
            if (text != null) {
                Icon(painterResource(R.drawable.ic_clock), null, Modifier.size(LIMIT_GLYPH), tint = colors.onSurfaceVariant)
                Text(text, color = colors.onSurface, style = style, maxLines = 1)
            }
        }
    }
    Row(Modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(LIMIT_GLYPH_GAP), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_terminal), null, Modifier.size(LIMIT_GLYPH), tint = colors.onSurfaceVariant)
        Text("${line.sessions}", color = colors.onSurface, style = style, maxLines = 1)
    }
}

/**
 * A [SMALL_BADGE] provider badge with [mark] (see [accountMark]), if any, on its bottom-right
 * corner: black on a white disc, set off from the badge by a ring in the card's color. The mark
 * hangs a little past the badge without taking room.
 */
@Composable
private fun BadgeWithMark(provider: String, mark: String?) {
    Box {
        ProviderBadge(provider, size = SMALL_BADGE)
        if (mark != null) {
            // Sized in dp, as the badge's letter: in sp a large font scale would overflow the disc.
            val letterStyle = with(LocalDensity.current) {
                MaterialTheme.typography.labelSmall.copy(fontSize = (MARK * 0.8f).toSp(), lineHeight = MARK.toSp(), fontWeight = FontWeight.Bold)
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp, y = 2.dp)
                    .size(MARK + MARK_RING * 2)
                    .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                    .padding(MARK_RING)
                    .background(Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(mark, color = Color.Black, style = letterStyle, maxLines = 1)
            }
        }
    }
}

/** `Codex Pro: 11% used, resets Friday 14:30, 4 active sessions`; the reset as [clock] words. */
@Composable
private fun limitDescription(line: LimitLine, clock: String?): String {
    val name = providerLabel(line.provider) + (line.account?.let { " $it" } ?: "")
    val parts = buildList {
        add(line.percent?.let { stringResource(R.string.limit_used, name, it) } ?: stringResource(R.string.limit_unknown, name))
        if (clock != null) add(stringResource(R.string.limit_resets_at, clock))
        add(pluralStringResource(R.plurals.limit_sessions, line.sessions, line.sessions))
    }
    return parts.joinToString(", ")
}

/**
 * [minutes] in words, as the Usage screen shows it ([remainingIn]): `3 days 4 hours`, `3 days`
 * from a day, else `2 hours 13 minutes`, `2 hours`, `13 minutes`.
 */
@Composable
internal fun durationWords(minutes: Long): String {
    val days = (minutes / MINUTES_PER_DAY).toInt()
    val hours = (minutes / 60).toInt()
    val rest = (minutes % 60).toInt()
    val dayHours = hours % 24
    return when {
        days > 0 && dayHours > 0 ->
            pluralStringResource(R.plurals.duration_days, days, days) + " " + pluralStringResource(R.plurals.duration_hours, dayHours, dayHours)
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
        // Line 2, muted: `12m · [C] repo`; accounts are on the Usage screen.
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Row(
            Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgoText(session.lastActivity, now, color = muted, style = MaterialTheme.typography.labelSmall)
            Text("·", Modifier.clearAndSetSemantics {}, color = muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            ProviderBadge(session.provider, size = SMALL_BADGE)
            Text(basename(session.cwd), color = muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
