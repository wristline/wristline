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
import dev.wristline.watch.data.Bridge
import dev.wristline.watch.data.Usage
import dev.wristline.watch.data.UsageWindow
import dev.wristline.watch.data.isoToMillis
import dev.wristline.watch.data.key
import java.time.Instant
import java.time.ZoneId
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
 * Per provider: a ring per limit window with the time left to its reset and the clock time it
 * resets at, and when the numbers were taken.
 */
@Composable
internal fun UsageContent(usage: List<Usage>, now: () -> Long) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val locale = LocalConfiguration.current.locales[0]
    val sorted = remember(usage) { usage.sortedWith(compareBy({ it.provider }, { it.account?.label })) }
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
                        provider.account?.let { account ->
                            Text(
                                if (account.estimated) stringResource(R.string.account_estimated, account.label) else account.label,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        UpdatedText(provider.updatedAt, now)
                    }
                }
                val firstRing = rings
                rings += provider.windows.size
                itemsIndexed(provider.windows, key = { _, window -> "window/$index/${window.id}" }) { i, window ->
                    WindowRow(
                        window,
                        now,
                        locale,
                        Modifier.edgeTransform(this, spec).minimumVerticalContentPadding(top = 0.dp, bottom = bottom),
                        fillDelayMs = if (ringsFilled) null else (firstRing + i) * RING_STAGGER_MS,
                        onFillStarted = { ringsFilled = true },
                    )
                }
            }
        }
    }
}

/** `Updated 4 min. ago`. Reads [now] itself: the minute tick recomposes this text, not the header. */
@Composable
private fun UpdatedText(updatedAt: String, now: () -> Long) {
    val at = now()
    CaptionText(
        if (isJustNow(updatedAt, at)) {
            stringResource(R.string.usage_updated_now)
        } else {
            stringResource(R.string.usage_updated, relativeTime(updatedAt, at))
        },
    )
}

/**
 * A window: its ring and percentage, its name and, when it has a reset time, the time left and the
 * clock time ([ResetTimes]). From its reset time on the window is over (as on the limit card, see
 * [currentLine]): an empty ring, a dash and `Reset`, until the bridge reports the next one.
 */
@Composable
private fun WindowRow(
    window: UsageWindow,
    now: () -> Long,
    locale: Locale,
    modifier: Modifier,
    fillDelayMs: Long?,
    onFillStarted: () -> Unit,
) {
    val resetsAt = remember(window.resetsAt) { isoToMillis(window.resetsAt) }
    // Changes once, at the reset: the tick recomposes the row then only.
    val passed by remember(resetsAt, now) { derivedStateOf { resetPassed(resetsAt, now()) } }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        // Centered as a group under the centered headings; 16dp keeps a wide row's ring clear of the
        // round edge in the lower half of the screen.
        modifier.fillMaxWidth().padding(horizontal = 16.dp).wrapContentWidth(Alignment.CenterHorizontally),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            PercentRing(if (passed) 0.0 else window.usedPercent, Modifier.fillMaxSize(), fillDelayMs = fillDelayMs, onFillStarted = onFillStarted)
            if (passed) {
                Text("—", color = muted, style = MaterialTheme.typography.labelSmall)
            } else {
                Text("${window.usedPercent.roundToInt()}%", style = MaterialTheme.typography.labelSmall)
            }
        }
        Column {
            Text(window.label ?: windowLabel(window), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                passed -> Text(stringResource(R.string.usage_reset_passed), color = muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                resetsAt != null -> ResetTimes(resetsAt, now, locale)
            }
        }
    }
}

/**
 * `⧗ 2h 13m` over `3:13 PM`: the time left to [resetsAt] in full units ([remainingFullText]) and the
 * clock time it resets at ([resetClock]), in the watch's 12 or 24-hour setting. Reads [now]
 * itself: the minute tick recomposes these two lines only.
 */
@Composable
private fun ResetTimes(resetsAt: Long, now: () -> Long, locale: Locale) {
    val colors = MaterialTheme.colorScheme
    val at = now()
    val left = minutesLeft(resetsAt, at)
    val description = stringResource(R.string.limit_resets_in, durationWords(left))
    Row(
        Modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(LIMIT_GLYPH_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_hourglass), null, Modifier.size(LIMIT_GLYPH), tint = colors.onSurfaceVariant)
        Text(
            remainingFullText(
                left,
                stringResource(R.string.limit_days),
                stringResource(R.string.limit_hours),
                stringResource(R.string.limit_minutes),
                stringResource(R.string.limit_under_minute),
            ),
            color = colors.onSurface,
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
    }
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    Text(
        resetClock(resetsAt, at, ZoneId.systemDefault(), locale, is24Hour),
        color = colors.onSurfaceVariant,
        style = MaterialTheme.typography.bodyExtraSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * [minutes] left in full units, at most two: days and hours from a day ([dayPattern] `%1$dd`,
 * [hourPattern] `%1$dh`), else hours and minutes ([minutePattern] `%1$dm`), a zero second unit left
 * out: `3d 4h`, `3d`, `2h 13m`, `2h`, `5m`; under a minute [underMinute] (`<1m`).
 */
internal fun remainingFullText(minutes: Long, dayPattern: String, hourPattern: String, minutePattern: String, underMinute: String): String {
    val days = minutes / MINUTES_PER_DAY
    val hours = minutes / 60 % 24
    val rest = minutes % 60
    val parts = when {
        days > 0 -> listOf(dayPattern.format(days), hourPattern.takeIf { hours > 0 }?.format(hours))
        hours > 0 -> listOf(hourPattern.format(hours), minutePattern.takeIf { rest > 0 }?.format(rest))
        rest > 0 -> listOf(minutePattern.format(rest))
        else -> return underMinute
    }
    return parts.filterNotNull().joinToString(" ")
}

/**
 * The clock time [resetsAt] falls on in [zone], as of [now]: the time alone today (`14:30`), with
 * the weekday within the next six days (`Fri 14:30`), with the date from seven days (`10/8 14:30`).
 * In [locale]'s short time, or in 24 hours when [is24Hour].
 */
internal fun resetClock(resetsAt: Long, now: Long, zone: ZoneId, locale: Locale, is24Hour: Boolean): String {
    val time = Instant.ofEpochMilli(resetsAt).atZone(zone)
    val timeFormat = if (is24Hour) DateTimeFormatter.ofPattern("H:mm", locale) else DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    val clock = timeFormat.format(time)
    return when (ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), time.toLocalDate())) {
        0L -> clock
        in 1L..6L -> time.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) + " " + clock
        else -> DateTimeFormatter.ofPattern("M/d", locale).format(time) + " " + clock
    }
}

/** A window's length in minutes: as reported, else known from Claude Code's ids; null when unknown. */
internal fun windowMinutes(window: UsageWindow): Int? = window.minutes ?: when (window.id) {
    "5h" -> 300
    "7d" -> 10_080
    else -> null
}

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
