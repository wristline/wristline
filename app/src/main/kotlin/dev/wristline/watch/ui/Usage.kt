package dev.wristline.watch.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextDefaults
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import dev.wristline.watch.R
import dev.wristline.watch.data.Account
import dev.wristline.watch.data.Bridge
import dev.wristline.watch.data.Usage
import dev.wristline.watch.data.UsageWindow
import dev.wristline.watch.data.isoToMillis
import dev.wristline.watch.data.key
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

@Composable
internal fun UsageScreen() {
    val usage by Bridge.usage.collectAsStateWithLifecycle()
    // Read by the time texts only (and by each row to see its reset pass), so the minute tick
    // recomposes just those, not the list.
    val now = rememberNowState()
    UsageContent(usage) { now.value }
}

/**
 * Per provider and account: a ring per limit window with the clock time it resets at and the time
 * left to it, and when the numbers were taken. Numbers, times and window names are in short English
 * with icons in every language; TalkBack reads them out in the watch's.
 */
@Composable
internal fun UsageContent(usage: List<Usage>, now: () -> Long) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val sorted = remember(usage) { usageOrder(usage) }
    // Only the last item's bottom value takes effect; it keeps the final row off the round edge.
    val bottom = TextDefaults.minimumBottomListContentPadding
    // The rings on screen fill in from zero one after another, once per visit: rings the list
    // composes later, as it scrolls, show their values straight away.
    var ringsFilled by remember { mutableStateOf(false) }
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
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
                    // The rows' 16dp at the sides: a long account label ends in an ellipsis, not under the round edge.
                    Column(
                        Modifier.fillMaxWidth().edgeTransform(this, spec).padding(start = 16.dp, top = 6.dp, end = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(providerLabel(provider.provider), style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            provider.account?.let { AccountText(it, Modifier.weight(1f, fill = false)) }
                            UpdatedText(provider.updatedAt, now)
                        }
                    }
                }
                val firstRing = rings
                rings += provider.windows.size
                itemsIndexed(provider.windows, key = { _, window -> "window/$index/${window.id}" }) { i, window ->
                    WindowRow(
                        window,
                        now,
                        Modifier.edgeTransform(this, spec).minimumVerticalContentPadding(top = 0.dp, bottom = bottom),
                        fillDelayMs = if (ringsFilled) null else (firstRing + i) * RING_STAGGER_MS,
                        onFillStarted = { ringsFilled = true },
                    )
                }
            }
        }
    }
}

/** The account's label as given, `(est.)` after it when only estimated; read out with the word in the watch's language. */
@Composable
private fun AccountText(account: Account, modifier: Modifier) {
    val spoken = if (account.estimated) stringResource(R.string.account_estimated, account.label) else account.label
    Text(
        if (account.estimated) "${account.label} (est.)" else account.label,
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
 * A window: its ring and percentage, its name ([windowAbbrev]) and, when it has a reset time, the
 * clock time and the time left ([ResetTimes]). From its reset time on the window is over (as on the
 * limit card, see [currentLine]): an empty ring, a dash and `Reset`, until the bridge reports the
 * next one. Read out as one sentence.
 */
@Composable
private fun WindowRow(
    window: UsageWindow,
    now: () -> Long,
    modifier: Modifier,
    fillDelayMs: Long?,
    onFillStarted: () -> Unit,
) {
    val resetsAt = remember(window.resetsAt) { isoToMillis(window.resetsAt) }
    // Changes once, at the reset: the tick recomposes the row then only.
    val passed by remember(resetsAt, now) { derivedStateOf { resetPassed(resetsAt, now()) } }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val name = windowWords(window)
    val percent = window.usedPercent.roundToInt()
    val spoken = if (passed) name + ", " + stringResource(R.string.usage_reset_passed) else stringResource(R.string.limit_used, name, percent)
    Row(
        // Centered as a group under the centered headings; 16dp keeps a wide row's ring clear of the
        // round edge in the lower half of the screen.
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .wrapContentWidth(Alignment.CenterHorizontally)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clearAndSetSemantics { contentDescription = spoken }, contentAlignment = Alignment.Center) {
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
 * within one first the entry without an account or of the account labelled as the default (`기본`,
 * `default`, `work`: the label given to the bridge's primary home), then the others by label
 * in any case, then by account id: the same entries are always in the same order. The bridge's own
 * order follows which of its homes reported first, and a `usage` event for a new entry adds it at
 * the end.
 */
internal fun usageOrder(usage: List<Usage>): List<Usage> =
    usage.sortedWith(
        compareBy(
            { it.provider },
            { if (isDefaultAccount(it.account)) 0 else 1 },
            { it.account?.label?.lowercase(Locale.ROOT) },
            { it.account?.id },
        ),
    )

private val DEFAULT_LABELS = setOf("기본", "default", "work")

private fun isDefaultAccount(account: Account?): Boolean = account == null || account.label.trim().lowercase(Locale.ROOT) in DEFAULT_LABELS

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
private val MONTH_DAY = DateTimeFormatter.ofPattern("M/d", Locale.US)

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
 * time alone today (`14:30`), with the weekday within the next six days (`Fri 14:30`), with the
 * date from seven (`10/8 14:30`; the weekday would name today's). The clock as [clockText].
 */
internal fun resetClockText(resetsAt: Long, now: Long, zone: ZoneId, is24Hour: Boolean, compact: Boolean = false): String {
    val time = Instant.ofEpochMilli(resetsAt).atZone(zone)
    val clock = clockText(time, is24Hour, compact)
    return when (daysFrom(now, time)) {
        0L -> clock
        in 1L..6L -> WEEKDAY.format(time) + " " + clock
        else -> MONTH_DAY.format(time) + " " + clock
    }
}

/**
 * [resetClockText] for TalkBack, in [locale]: [today] (`today %1$s`) with the time, the weekday in
 * full within the next six days (`Friday 14:30`), else the date (`October 8, 2026 14:30`); in the
 * locale's 12-hour time unless [is24Hour].
 */
internal fun resetClockWords(resetsAt: Long, now: Long, zone: ZoneId, locale: Locale, is24Hour: Boolean, today: String): String {
    val time = Instant.ofEpochMilli(resetsAt).atZone(zone)
    val clock = clockWords(time, locale, is24Hour)
    return when (daysFrom(now, time)) {
        0L -> today.format(clock)
        in 1L..6L -> time.dayOfWeek.getDisplayName(TextStyle.FULL, locale) + " " + clock
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
