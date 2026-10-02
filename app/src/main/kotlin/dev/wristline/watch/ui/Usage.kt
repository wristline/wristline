package dev.wristline.watch.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import dev.wristline.watch.R
import dev.wristline.watch.data.Account
import dev.wristline.watch.data.AccountBook
import dev.wristline.watch.data.AccountLook
import dev.wristline.watch.data.Bridge
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.Usage
import dev.wristline.watch.data.UsageWindow
import dev.wristline.watch.data.accountKey
import dev.wristline.watch.data.accountsOf
import dev.wristline.watch.data.isoToMillis
import dev.wristline.watch.data.key
import dev.wristline.watch.data.look
import dev.wristline.watch.data.ordered
import dev.wristline.watch.data.seen
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

// Between one ring's start filling in and the next's.
private const val RING_STAGGER_MS = 60L

// A window card's ring, and the gap between it and the texts.
private val USAGE_RING = 44.dp
private val USAGE_RING_GAP = 10.dp

@Composable
internal fun UsageScreen() {
    val usage by Bridge.usage.collectAsStateWithLifecycle()
    val sessions by Bridge.sessions.collectAsStateWithLifecycle()
    val accounts by Bridge.prefs.accounts.collectAsStateWithLifecycle()
    // Read by the time texts only (and by each row to see its reset pass), so the minute tick
    // recomposes just those, not the list.
    val now = rememberNowState()
    UsageContent(usage, sessions, accounts) { now.value }
}

/**
 * Per provider and account: a ring per limit window with the clock time it resets at and the time
 * left to it, and when the numbers were taken. Numbers, times and window names are in short English
 * with icons in every language; TalkBack reads them out in the watch's. An account of a provider
 * the list marks ([markedProviders], with the [sessions]) has its badge before its name, the key
 * to the list's marks; the name and the order as [accounts] has them.
 */
@Composable
internal fun UsageContent(usage: List<Usage>, sessions: List<Session> = emptyList(), accounts: AccountBook = AccountBook(), now: () -> Long) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val book = rememberAccountBook(accounts, usage, sessions)
    val marked = remember(usage, sessions) { markedProviders(usage, sessions) }
    val sorted = remember(usage, book) { usageOrder(usage, book) }
    // Only the last item's bottom value takes effect; it keeps the final card off the round edge.
    val bottom = CardDefaults.minimumVerticalListContentPadding
    // The rings on screen fill in from zero one after another, once per visit: rings the list
    // composes later, as it scrolls, show their values straight away.
    var ringsFilled by remember { mutableStateOf(false) }
    // The scaffold's content padding is the responsive one (a share of the screen), so the list's
    // items, as on the other screens, need no insets of their own to clear the round edge; near
    // the top and bottom edges they shrink and fade as they scroll ([rememberTransformationSpec]).
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            flingBehavior = TransformingLazyColumnDefaults.snapFlingBehavior(listState),
            rotaryScrollableBehavior = RotaryScrollableDefaults.snapBehavior(listState),
        ) {
            item(key = "title") {
                ListHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, spec)
                        .minimumVerticalContentPadding(ListHeaderDefaults.minimumTopListContentPadding),
                    transformation = SurfaceTransformation(spec),
                ) { Text(stringResource(R.string.usage_title)) }
            }
            if (usage.isEmpty()) {
                item(key = "empty") {
                    BodyText(
                        stringResource(R.string.usage_empty),
                        Modifier.edgeTransform(this, spec).minimumVerticalContentPadding(top = 0.dp, bottom = bottom),
                    )
                }
            }
            var rings = 0
            // Keys include the position: nothing in the protocol stops a snapshot from repeating a
            // [Usage.key], and a repeated key would crash the list.
            sorted.forEachIndexed { index, provider ->
                item(key = "provider/$index/${provider.key}") {
                    ListSubHeader(
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                    ) {
                        Column {
                            Text(providerLabel(provider.provider), style = MaterialTheme.typography.titleSmall)
                            // A long account label ends in an ellipsis, the updated time after it.
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                provider.account?.let { account ->
                                    val look = book.look(provider.provider, account)
                                    if (provider.provider in marked) BadgeWithMark(provider.provider, look, decorative = true)
                                    AccountText(account, look, Modifier.weight(1f, fill = false))
                                }
                                UpdatedText(provider.updatedAt, now)
                            }
                        }
                    }
                }
                val firstRing = rings
                rings += provider.windows.size
                itemsIndexed(provider.windows, key = { _, window -> "window/$index/${window.id}" }) { i, window ->
                    WindowCard(
                        window,
                        now,
                        Modifier.transformedHeight(this, spec).minimumVerticalContentPadding(top = 0.dp, bottom = bottom),
                        SurfaceTransformation(spec),
                        fillDelayMs = if (ringsFilled) null else (firstRing + i) * RING_STAGGER_MS,
                        onFillStarted = { ringsFilled = true },
                    )
                }
            }
        }
    }
}

/**
 * The account's name ([look]: the nickname, else the label as given), `(est.)` after it when only
 * estimated; read out with the word in the watch's language.
 */
@Composable
private fun AccountText(account: Account, look: AccountLook, modifier: Modifier) {
    val name = look.name
    val spoken = if (account.estimated) stringResource(R.string.account_estimated, name) else name
    Text(
        if (account.estimated) "$name (est.)" else name,
        modifier.semantics { contentDescription = spoken },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** `⟲ 4m`, read out `Updated 4 minutes ago`. Reads [now] itself: the minute tick recomposes this text, not the header. */
@Composable
private fun UpdatedText(updatedAt: String, now: () -> Long) {
    val at = now()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val spoken = if (isJustNow(updatedAt, at)) {
        stringResource(R.string.usage_updated_now)
    } else {
        stringResource(R.string.usage_updated, relativeTime(updatedAt, at))
    }
    Row(
        Modifier.clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(LIMIT_GLYPH_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_history), null, Modifier.size(LIMIT_GLYPH), tint = muted)
        Text(agoText(updatedAt, at), color = muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/**
 * A window as a card, not clickable: its ring and percentage, then its name ([windowAbbrev]) and,
 * when it has a reset time, the clock time and the time left ([ResetTimes]), left-aligned. From its
 * reset time on the window is over (as on the limit card, see [currentLine]): an empty ring, a dash
 * and `Reset`, until the bridge reports the next one. Read out as one sentence.
 */
@Composable
private fun WindowCard(
    window: UsageWindow,
    now: () -> Long,
    modifier: Modifier,
    transformation: SurfaceTransformation,
    fillDelayMs: Long?,
    onFillStarted: () -> Unit,
) {
    val resetsAt = remember(window.resetsAt) { isoToMillis(window.resetsAt) }
    // Changes once, at the reset: the tick recomposes the card then only.
    val passed by remember(resetsAt, now) { derivedStateOf { resetPassed(resetsAt, now()) } }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val name = windowWords(window)
    val percent = window.usedPercent.roundToInt()
    val spoken = if (passed) name + ", " + stringResource(R.string.usage_reset_passed) else stringResource(R.string.limit_used, name, percent)
    // The Card without onClick: nothing for TalkBack to activate, read out as one.
    Card(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, transformation = transformation) {
        Row(horizontalArrangement = Arrangement.spacedBy(USAGE_RING_GAP), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(USAGE_RING).clearAndSetSemantics { contentDescription = spoken }, contentAlignment = Alignment.Center) {
                PercentRing(if (passed) 0.0 else window.usedPercent, Modifier.fillMaxSize(), fillDelayMs = fillDelayMs, onFillStarted = onFillStarted)
                if (passed) {
                    Text("—", color = muted, style = MaterialTheme.typography.labelSmall)
                } else {
                    Text("$percent%", style = MaterialTheme.typography.labelSmall)
                }
            }
            Column {
                Text(
                    windowAbbrev(window),
                    Modifier.clearAndSetSemantics {},
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when {
                    passed -> Text("Reset", Modifier.clearAndSetSemantics {}, color = muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    resetsAt != null -> ResetTimes(resetsAt, now)
                }
            }
        }
    }
}

/**
 * `◷ Fri 14:30` over `in 2h 13m`: the clock time [resetsAt] falls on ([resetClockText]), in the
 * watch's 12 or 24-hour setting, and the time left to it ([remainingIn]); read out as `resets Friday
 * 14:30, 2 hours 13 minutes left`. Reads [now] itself: the minute tick recomposes these two lines only.
 */
@Composable
private fun ResetTimes(resetsAt: Long, now: () -> Long) {
    val colors = MaterialTheme.colorScheme
    val at = now()
    val left = minutesLeft(resetsAt, at)
    val zone = ZoneId.systemDefault()
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val words = resetClockWords(resetsAt, at, zone, LocalConfiguration.current.locales[0], is24Hour, stringResource(R.string.limit_today))
    val spoken = stringResource(R.string.limit_resets_at, words) + ", " + stringResource(R.string.limit_left, durationWords(left))
    Column(Modifier.clearAndSetSemantics { contentDescription = spoken }) {
        Row(horizontalArrangement = Arrangement.spacedBy(LIMIT_GLYPH_GAP), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_clock), null, Modifier.size(LIMIT_GLYPH), tint = colors.onSurfaceVariant)
            Text(
                resetClockText(resetsAt, at, zone, is24Hour),
                color = colors.onSurface,
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
            )
        }
        Text(remainingIn(left), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyExtraSmall, maxLines = 1)
    }
}

/**
 * Usage entries in the order the app shows them (the limit card's lines too): by provider, and
 * within one first the entry without an account, then the accounts in [book]'s order (the user's,
 * else the primary home's first, else the earlier seen; see [AccountBook.ordered]), then any it
 * does not know by label in any case and by id: the same entries are always in the same order. The
 * bridge's own order follows which of its homes reported first, and a `usage` event for a new entry
 * adds it at the end.
 */
internal fun usageOrder(usage: List<Usage>, book: AccountBook = AccountBook().seen(accountsOf(usage, emptyList()), 0)): List<Usage> {
    val ranks = usage.map { it.provider }.distinct().associateWith { provider -> book.ordered(provider).withIndex().associate { (i, key) -> key to i } }
    return usage.sortedWith(
        compareBy(
            { it.provider },
            { it.account != null },
            { entry -> entry.account?.let { ranks[entry.provider]?.get(accountKey(entry.provider, it)) } ?: Int.MAX_VALUE },
            { it.account?.label?.lowercase(Locale.ROOT) },
            { it.account?.id },
        ),
    )
}

/**
 * [minutes] left in full units, at most two, in short English in every language: days and hours
 * from a day, else hours and minutes, a zero second unit left out: `in 3d 4h`, `in 3d`, `in 2h 13m`,
 * `in 2h`, `in 5m`; under a minute `in <1m`.
 */
internal fun remainingIn(minutes: Long): String {
    val days = minutes / MINUTES_PER_DAY
    val hours = minutes / 60 % 24
    val rest = minutes % 60
    val parts = when {
        days > 0 -> listOf("${days}d", "${hours}h".takeIf { hours > 0 })
        hours > 0 -> listOf("${hours}h", "${rest}m".takeIf { rest > 0 })
        rest > 0 -> listOf("${rest}m")
        else -> listOf("<1m")
    }
    return "in " + parts.filterNotNull().joinToString(" ")
}

private val CLOCK_24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private val CLOCK_12 = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val CLOCK_12_DIGITS = DateTimeFormatter.ofPattern("h:mm", Locale.US)
private val WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.US)
internal val MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)

/** Calendar days in [zone] from [now] to [time]: 0 today, 1 tomorrow; negative before today. */
private fun daysFrom(now: Long, time: ZonedDateTime): Long =
    ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(time.zone).toLocalDate(), time.toLocalDate())

/** [time]'s clock in English whatever the language: `14:30` when [is24Hour], else `2:30 PM`, or with [compact] `2:30p`. */
internal fun clockText(time: ZonedDateTime, is24Hour: Boolean, compact: Boolean = false): String = when {
    is24Hour -> CLOCK_24.format(time)
    compact -> CLOCK_12_DIGITS.format(time) + if (time.hour < 12) "a" else "p"
    else -> CLOCK_12.format(time)
}

/** [clockText] for TalkBack: `14:30` when [is24Hour], else the 12-hour time of [locale] (`2:30 PM`, `오후 2:30`). */
internal fun clockWords(time: ZonedDateTime, locale: Locale, is24Hour: Boolean): String =
    if (is24Hour) CLOCK_24.format(time) else DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(time)

/**
 * The clock time [resetsAt] falls on in [zone], as of [now], in English whatever the language: the
 * time alone today (`14:30`), with the weekday within the next seven days (`Fri 14:30`; seven days
 * on, today's weekday names next week's, as today shows the time alone), with the date from eight,
 * the month's English abbreviation (`Oct 9 14:30`), which no reader takes for 10 September. The
 * clock as [clockText].
 */
internal fun resetClockText(resetsAt: Long, now: Long, zone: ZoneId, is24Hour: Boolean, compact: Boolean = false): String {
    val time = Instant.ofEpochMilli(resetsAt).atZone(zone)
    val clock = clockText(time, is24Hour, compact)
    return when (daysFrom(now, time)) {
        0L -> clock
        in 1L..7L -> WEEKDAY.format(time) + " " + clock
        else -> MONTH_DAY.format(time) + " " + clock
    }
}

/**
 * [resetClockText] for TalkBack, in [locale]: [today] (`today %1$s`) with the time, the weekday in
 * full within the next seven days (`Friday 14:30`), else the date (`October 9, 2026 14:30`); in the
 * locale's 12-hour time unless [is24Hour].
 */
internal fun resetClockWords(resetsAt: Long, now: Long, zone: ZoneId, locale: Locale, is24Hour: Boolean, today: String): String {
    val time = Instant.ofEpochMilli(resetsAt).atZone(zone)
    val clock = clockWords(time, locale, is24Hour)
    return when (daysFrom(now, time)) {
        0L -> today.format(clock)
        in 1L..7L -> time.dayOfWeek.getDisplayName(TextStyle.FULL, locale) + " " + clock
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(time) + " " + clock
    }
}

/** A window's length in minutes: as reported, else known from Claude Code's ids; null when unknown. */
internal fun windowMinutes(window: UsageWindow): Int? = window.minutes ?: when (window.id) {
    "5h" -> 300
    "7d" -> 10_080
    else -> null
}

/**
 * A window's name on screen, short and in English: the bridge's label (`5h`, `7d Opus`), else its
 * length (`5h`, `7d`, `30d`, `90m`), else its id.
 */
internal fun windowAbbrev(window: UsageWindow): String {
    window.label?.let { return it }
    val minutes = windowMinutes(window)?.takeIf { it > 0 } ?: return window.id
    return when {
        minutes % MINUTES_PER_DAY == 0 -> "${minutes / MINUTES_PER_DAY}d"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes}m"
    }
}

/**
 * A window's name read out: its length in words ([windowLabel]) and what the bridge's label adds
 * to the length (`7d Opus` is `Weekly limit Opus`, `주간 한도 Opus`); a label that does not start
 * with the length (`Spend`) as it is.
 */
@Composable
private fun windowWords(window: UsageWindow): String {
    val rest = labelAfterLength(window) ?: return window.label.orEmpty()
    return windowLabel(window) + rest
}

/**
 * What a window's label says after its length ([windowAbbrev] without the label): ` Opus` of
 * `7d Opus`, empty for `7d` or without a label; null for a label that does not start with it.
 */
internal fun labelAfterLength(window: UsageWindow): String? {
    val label = window.label ?: return ""
    val length = windowAbbrev(window.copy(label = null))
    return if (label.startsWith(length)) label.removePrefix(length) else null
}

/** A window's length in words, for TalkBack: `5-hour limit`, `Weekly limit`; its id when unknown. */
@Composable
internal fun windowLabel(window: UsageWindow): String {
    val minutes = windowMinutes(window)
    return when {
        minutes == null || minutes <= 0 -> window.id
        minutes == 300 -> stringResource(R.string.usage_window_5h)
        minutes == 10_080 -> stringResource(R.string.usage_window_7d)
        minutes % 1_440 == 0 -> stringResource(R.string.usage_window_days, minutes / 1_440)
        minutes % 60 == 0 -> stringResource(R.string.usage_window_hours, minutes / 60)
        else -> stringResource(R.string.usage_window_minutes, minutes)
    }
}
