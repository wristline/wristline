package dev.wristline.watch.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
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
import androidx.wear.compose.material3.touchTargetAwareSize
import dev.wristline.watch.R
import dev.wristline.watch.data.AccountBook
import dev.wristline.watch.data.AccountLook
import dev.wristline.watch.data.Bridge
import dev.wristline.watch.data.Conn
import dev.wristline.watch.data.PendingRequest
import dev.wristline.watch.data.ProviderId
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.SessionStatus
import dev.wristline.watch.data.TaskProgress
import dev.wristline.watch.data.Usage
import dev.wristline.watch.data.UsageWindow
import dev.wristline.watch.data.accountsOf
import dev.wristline.watch.data.isoToMillis
import dev.wristline.watch.data.look
import dev.wristline.watch.data.seen
import java.time.ZoneId
import kotlin.math.roundToInt

/** The icon buttons at the top of the list: the library's small size, the minimum touch target. */
private val ACTION_SIZE = IconButtonDefaults.SmallButtonSize
private val ACTION_GAP = 8.dp

/** The provider badge beside labelSmall text (the limit card, a session card's second line). */
private val SMALL_BADGE = 14.dp

/** The limit card's glyphs (reset, sessions; the Usage screen's too), the gap after one, and the gaps between groups and lines. */
internal val LIMIT_GLYPH = 12.dp
internal val LIMIT_GLYPH_GAP = 3.dp
private val LIMIT_GROUP_GAP = 6.dp
private val LIMIT_LINE_GAP = 2.dp

/** The account mark on a [SMALL_BADGE] badge (see [BadgeWithMark]), its letter, and the ring around it in the card's color. */
private val MARK = 9.dp
private val MARK_LETTER = 7.dp
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
    val accounts by Bridge.prefs.accounts.collectAsStateWithLifecycle()
    val ask = rememberQuickAsk(onStarted = onAsk)
    // Read by the cards' time only, so the minute tick recomposes just the visible cards' time.
    val now = rememberNowState()
    SessionListContent(
        conn = conn,
        sessions = sessions,
        requests = requests,
        usage = usage,
        accounts = accounts,
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
    /** The stored marks and names; previews leave them out and get the automatic ones. */
    accounts: AccountBook = AccountBook(),
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
    val marked = remember(usage, sessions) { markedProviders(usage, sessions) }
    val book = rememberAccountBook(accounts, usage, sessions)
    val limits = remember(usage, sessions, marked, book) { limitLines(usage, sessions, marked, book) }
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
                    FilledTonalIconButton(onClick = onSettings, modifier = Modifier.touchTargetAwareSize(ACTION_SIZE), shapes = shapes) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings_title), Modifier.size(iconSize))
                    }
                    FilledIconButton(onClick = onAsk, modifier = Modifier.touchTargetAwareSize(ACTION_SIZE), shapes = shapes) {
                        Icon(painterResource(R.drawable.ic_mic), stringResource(R.string.ask_button), Modifier.size(iconSize))
                    }
                    FilledTonalIconButton(onClick = onAskHistory, modifier = Modifier.touchTargetAwareSize(ACTION_SIZE), shapes = shapes) {
                        Icon(painterResource(R.drawable.ic_history), stringResource(R.string.ask_history_title), Modifier.size(iconSize))
                    }
                }
            }
            if (oldestRequest != null) {
                item(key = "requests") {
                    Button(
                        onClick = { onRequest(oldestRequest.id) },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).animateItemCalmly(this),
                        transformation = SurfaceTransformation(spec),
                        // Fixed yellow, not the theme's tertiary: watch colors could make it any hue.
                        colors = ButtonDefaults.buttonColors(containerColor = Status.Attention, contentColor = Color.Black),
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
                    account = session.account?.takeIf { session.provider in marked }?.let { book.look(session.provider, it) },
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
    /** How the account looks when its provider is one of [markedProviders]; else null. */
    val account: AccountLook? = null,
)

/**
 * [stored] as it is once the Bridge has seen these [usage] entries' and [sessions]' accounts: an
 * account new to it already has its mark here, before the stored book catches up (and in previews,
 * which store none).
 */
@Composable
internal fun rememberAccountBook(stored: AccountBook, usage: List<Usage>, sessions: List<Session>): AccountBook =
    remember(stored, usage, sessions) { stored.seen(accountsOf(usage, sessions), System.currentTimeMillis()) }

/**
 * The limit card's lines: one per usage entry with windows (the bridge sends the accounts logged in
 * now only), in [usageOrder], and one for a provider with sessions but no usage. A line's numbers
 * are its entry's [shortWindow]; without one it reads as without usage. It counts the provider's
 * sessions of its account; the first line of a provider also counts those without an account or of
 * an account without a line. A line of a provider in [marked] has its account's look from [book].
 */
internal fun limitLines(
    usage: List<Usage>,
    sessions: List<Session>,
    marked: Set<String> = markedProviders(usage, sessions),
    book: AccountBook = AccountBook().seen(accountsOf(usage, sessions), 0),
): List<LimitLine> {
    val entries = usageOrder(usage.filter { it.windows.isNotEmpty() }, book).groupBy { it.provider }
    val live = sessions.groupBy { it.provider }
    return (entries.keys + live.keys).sorted().flatMap { provider ->
        val own = live[provider].orEmpty()
        val accounts = entries[provider] ?: return@flatMap listOf(LimitLine(provider, null, null, own.size))
        val counts = own.groupingBy { session ->
            accounts.indexOfFirst { it.account != null && it.account.id == session.account?.id }.coerceAtLeast(0)
        }.eachCount()
        accounts.mapIndexed { i, entry ->
            val window = shortWindow(entry)
            val account = entry.account?.takeIf { provider in marked }?.let { book.look(provider, it) }
            LimitLine(provider, window?.usedPercent?.roundToInt(), isoToMillis(window?.resetsAt), counts[i] ?: 0, account)
        }
    }
}

/**
 * The providers whose limit lines and session cards mark their account ([BadgeWithMark]): those
 * with more than one account among the [usage] entries with windows (the limit card's lines; one
 * without an account counts as one) and the [sessions] with an account.
 */
internal fun markedProviders(usage: List<Usage>, sessions: List<Session>): Set<String> {
    val lines = usage.filter { it.windows.isNotEmpty() }.map { it.provider to it.account?.id }
    val accounts = sessions.mapNotNull { session -> session.account?.let { session.provider to it.id } }
    return (lines + accounts).distinct().groupingBy { it.first }.eachCount().filterValues { it > 1 }.keys
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
 * Wider than the card even so (a large font), each line's session count goes under its clock, so
 * the user's font size holds. Only if that is still too wide (a small watch at the largest font)
 * is the table scaled down to fit: a line never loses its end under the card's edge.
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
        val compact = listOf(0, CELL_CLOCK_COMPACT, LIMIT_CELLS - 1)
        val columns = if (width(widths(full)) <= constraints.maxWidth) full else compact
        // The count on a second line, at the clock's start: what the first line keeps is the badge,
        // the percentage and the clock.
        val wrap = width(widths(columns)) > constraints.maxWidth
        val count = LIMIT_CELLS - 1
        val firstLine = if (wrap) columns - count else columns
        val widths = widths(firstLine)
        val starts = starts(widths)
        val countX = if (wrap) starts[1] else 0
        val firstHeights = rows.map { row -> firstLine.maxOf { row[it].height } }
        val heights = rows.mapIndexed { i, row -> firstHeights[i] + if (wrap) row[count].height else 0 }
        val height = heights.sum() + lineGap * (rows.size - 1)
        val natural = maxOf(width(widths), if (wrap) countX + rows.maxOf { it[count].width } else 0)
        val scale = if (natural > constraints.maxWidth) constraints.maxWidth / natural.toFloat() else 1f
        layout(constraints.constrainWidth((natural * scale).roundToInt()), constraints.constrainHeight((height * scale).roundToInt())) {
            // Scaled from the cell's start, so a cell mirrored in right-to-left stays in its column.
            val origin = TransformOrigin(if (layoutDirection == LayoutDirection.Rtl) 1f else 0f, 0f)
            var y = 0
            rows.forEachIndexed { i, row ->
                val cells = firstLine.mapIndexed { column, cell -> Triple(cell, starts[column], y + (firstHeights[i] - row[cell].height) / 2) } +
                    if (wrap) listOf(Triple(count, countX, y + firstHeights[i])) else emptyList()
                cells.forEach { (cell, x, top) ->
                    if (scale == 1f) {
                        row[cell].placeRelative(x, top)
                    } else {
                        row[cell].placeRelativeWithLayer((x * scale).roundToInt(), (top * scale).roundToInt()) {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = origin
                        }
                    }
                }
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
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val today = stringResource(R.string.limit_today)
    val clock by remember(resetsAt, now, context, locale, today) {
        derivedStateOf {
            resetsAt?.let {
                val at = now()
                val zone = ZoneId.systemDefault()
                // Read on each tick: a change of the 12/24-hour setting is no configuration change,
                // and the card is not recomposed for it.
                val is24Hour = DateFormat.is24HourFormat(context)
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
        BadgeWithMark(line.provider, line.account)
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
 * A [SMALL_BADGE] provider badge with [account]'s mark, if any, on its bottom-right corner: its
 * glyph on its color's disc ([markColor], white by default), set off from the badge by a ring in
 * the card's color. The mark hangs a little past the badge without taking room. Read out as
 * `Codex Work` (the nickname, if any), or not at all when [decorative] (a name beside it says it).
 * [scale] enlarges the whole of it, for the Accounts screen's preview.
 */
@Composable
internal fun BadgeWithMark(provider: String, account: AccountLook?, scale: Float = 1f, decorative: Boolean = false) {
    val description = account?.let { providerLabel(provider) + " " + it.name }
    val semantics = when {
        decorative -> Modifier.clearAndSetSemantics {}
        description != null -> Modifier.clearAndSetSemantics { contentDescription = description }
        else -> Modifier
    }
    Box(semantics) {
        ProviderBadge(provider, size = SMALL_BADGE * scale)
        if (account != null) {
            val color = markColor(account.color)
            // Sized in dp, as the badge's letter: in sp a large font scale would overflow the disc.
            val letterStyle = with(LocalDensity.current) {
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = (MARK_LETTER * scale).toSp(),
                    lineHeight = (MARK * scale).toSp(),
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp * scale, y = 2.dp * scale)
                    .size((MARK + MARK_RING * 2) * scale)
                    .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                    .padding(MARK_RING * scale)
                    .background(color.disc, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(account.mark, color = color.glyph, style = letterStyle, maxLines = 1)
            }
        }
    }
}

/** `Codex Pro: 11% used, resets Friday 14:30, 4 active sessions`; the reset as [clock] words. */
@Composable
private fun limitDescription(line: LimitLine, clock: String?): String {
    val name = providerLabel(line.provider) + (line.account?.let { " " + it.name } ?: "")
    val parts = buildList {
        add(line.percent?.let { stringResource(R.string.limit_used, name, it) } ?: stringResource(R.string.limit_unknown, name))
        if (clock != null) add(stringResource(R.string.limit_resets_at, clock))
        add(pluralStringResource(R.plurals.limit_sessions, line.sessions, line.sessions))
    }
    return parts.joinToString(", ")
}

/**
 * [minutes] in words, as the Usage screen shows it ([remainingIn]): `3 days 4 hours`, `3 days`
 * from a day, else `2 hours 13 minutes`, `2 hours`, `13 minutes`; under a minute `less than a minute`.
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
        rest > 0 -> pluralStringResource(R.plurals.duration_minutes, rest, rest)
        else -> stringResource(R.string.duration_under_minute)
    }
}

// Builds its modifier and transformation itself, as ItemRow does: made by the caller they would be
// new on every recomposition of the list, and the card could never skip.
@Composable
private fun TransformingLazyColumnItemScope.SessionCard(
    session: Session,
    /** How its account looks when its provider is one of [markedProviders]; else null. */
    account: AccountLook?,
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
        // Line 1: the status dot, the session's name, which tells cards apart (the largest text),
        // and after it, small and muted, the model and effort when known (`Fable 5.1 · medium`,
        // here only, not over the transcript). The dot sits on the top line: near the screen's
        // bottom edge the list morphs the card's lower corners, which would hide it lower down.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(session.status)
            TitleWithSetup(sessionTitle(session), session.model, session.effort)
        }
        // Line 2, muted: `12m · [C] repo`, the badge marked with the account as on the limit card;
        // `12m · 3/7 · [C] repo` while a running agent has a task list.
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Row(
            Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgoText(session.lastActivity, now, color = muted, style = MaterialTheme.typography.labelSmall)
            Text("·", Modifier.clearAndSetSemantics {}, color = muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            cardProgress(session)?.let { progress ->
                val spoken = pluralStringResource(R.plurals.tasks_done, progress.total, progress.done, progress.total)
                Text(
                    cardProgressText(progress),
                    Modifier.clearAndSetSemantics { contentDescription = spoken },
                    color = muted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
                Text("·", Modifier.clearAndSetSemantics {}, color = muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            BadgeWithMark(session.provider, account)
            Text(basename(session.cwd), color = muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The task list a session card shows: while running with one; done kept within 0..total. */
internal fun cardProgress(session: Session): TaskProgress? =
    session.progress?.takeIf { session.status == SessionStatus.RUNNING && it.total > 0 }
        ?.let { it.copy(done = it.done.coerceIn(0, it.total)) }

/** `3/7`, in every locale. */
internal fun cardProgressText(progress: TaskProgress): String = "${progress.done}/${progress.total}"

/** A session card's title space never shrinks below this for the model and effort after it. */
private val TITLE_MIN = 80.dp

/** The model and effort after a title never shrink below this (or their own width, if less). */
private val SETUP_MIN = 70.dp
private val SETUP_GAP = 6.dp

/**
 * What a card shows of a session's [model] and [effort] after its title, the fullest first:
 * `Fable 5.1 · medium`, `Fable 5.1`, then the model's first word (`Fable`); the effort alone
 * without a model. Empty when neither is known.
 */
internal fun setupVariants(model: String?, effort: String?): List<String> {
    val name = model?.trim()?.takeIf { it.isNotEmpty() }
    val level = effort?.trim()?.takeIf { it.isNotEmpty() }
    if (name == null) return listOfNotNull(level)
    return listOfNotNull(level?.let { "$name · $it" }, name, name.substringBefore(' ')).distinct()
}

/**
 * Which of the [setup] variants ([setupVariants], their widths) goes after a title [title] wide on a
 * line [available] wide, [gap] between them, and how wide it is: the fullest that leaves the title
 * all its width or at least [titleMin], else the last, at least [setupMin] (or its own width, if
 * less). Null without variants.
 */
internal fun setupChoice(title: Int, setup: List<Int>, available: Int, gap: Int, titleMin: Int, setupMin: Int): Pair<Int, Int>? {
    if (setup.isEmpty()) return null
    // What the setup may take and still leave the title its share.
    val room = available - gap - minOf(title, titleMin)
    val index = setup.indices.firstOrNull { setup[it] <= room } ?: setup.lastIndex
    val width = setup[index]
    return index to minOf(width, maxOf(room, minOf(width, setupMin)))
}

/**
 * A card's title, ellipsized, then the model and effort small and muted on its baseline, as much
 * of them as [setupChoice] leaves room for. Read out as the title, then `Fable 5.1, medium`.
 */
@Composable
private fun RowScope.TitleWithSetup(title: String, model: String?, effort: String?) {
    val variants = remember(model, effort) { setupVariants(model, effort) }
    val spoken = listOfNotNull(title, listOfNotNull(model, effort).joinToString(", ").takeIf { it.isNotEmpty() }).joinToString(", ")
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Layout(
        content = {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            variants.forEach { Text(it, color = muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        modifier = Modifier.weight(1f).clearAndSetSemantics { contentDescription = spoken },
    ) { measurables, constraints ->
        val titleMeasurable = measurables.first()
        val setup = measurables.drop(1)
        val gap = SETUP_GAP.roundToPx()
        val choice = setupChoice(
            titleMeasurable.maxIntrinsicWidth(constraints.maxHeight),
            setup.map { it.maxIntrinsicWidth(constraints.maxHeight) },
            constraints.maxWidth,
            gap,
            TITLE_MIN.roundToPx(),
            SETUP_MIN.roundToPx(),
        )
        val setupPlaceable = choice?.let { (index, width) -> setup[index].measure(Constraints(maxWidth = width)) }
        val titleMax = if (setupPlaceable == null) constraints.maxWidth else (constraints.maxWidth - gap - setupPlaceable.width).coerceAtLeast(0)
        val titlePlaceable = titleMeasurable.measure(Constraints(maxWidth = titleMax))
        // The setup sits on the title's baseline.
        val titleBaseline = titlePlaceable[FirstBaseline]
        val setupTop = setupPlaceable?.let { titleBaseline - it[FirstBaseline] } ?: 0
        val top = minOf(0, setupTop)
        val height = maxOf(titlePlaceable.height, (setupPlaceable?.height ?: 0) + setupTop) - top
        layout(constraints.maxWidth, constraints.constrainHeight(height)) {
            titlePlaceable.placeRelative(0, -top)
            setupPlaceable?.placeRelative(titlePlaceable.width + gap, setupTop - top)
        }
    }
}
