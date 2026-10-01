package dev.wristline.watch.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
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
import dev.wristline.watch.data.key
import java.util.Locale
import kotlin.math.roundToInt

// Between one ring's start filling in and the next's.
private const val RING_STAGGER_MS = 60L

@Composable
internal fun UsageScreen() {
    val usage by Bridge.usage.collectAsStateWithLifecycle()
    UsageContent(usage, rememberNow())
}

/** Per provider: a ring per limit window with its reset time, and when the numbers were taken. */
@Composable
internal fun UsageContent(usage: List<Usage>, now: Long) {
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
                    Column(
                        Modifier.fillMaxWidth().edgeTransform(this, spec).padding(top = 6.dp),
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
                        CaptionText(
                            if (isJustNow(provider.updatedAt, now)) {
                                stringResource(R.string.usage_updated_now)
                            } else {
                                stringResource(R.string.usage_updated, relativeTime(provider.updatedAt, now))
                            },
                        )
                    }
                }
                val firstRing = rings
                rings += provider.windows.size
                itemsIndexed(provider.windows, key = { _, window -> "window/$index/${window.id}" }) { i, window ->
                    WindowRow(
                        window,
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

@Composable
private fun WindowRow(
    window: UsageWindow,
    locale: Locale,
    modifier: Modifier,
    fillDelayMs: Long?,
    onFillStarted: () -> Unit,
) {
    Row(
        // Centered as a group under the centered headings; 16dp keeps a wide row's ring clear of the
        // round edge in the lower half of the screen.
        modifier.fillMaxWidth().padding(horizontal = 16.dp).wrapContentWidth(Alignment.CenterHorizontally),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            PercentRing(window.usedPercent, Modifier.fillMaxSize(), fillDelayMs = fillDelayMs, onFillStarted = onFillStarted)
            Text("${window.usedPercent.roundToInt()}%", style = MaterialTheme.typography.labelSmall)
        }
        Column {
            Text(window.label ?: windowLabel(window), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            clockTime(window.resetsAt, locale)?.let {
                Text(
                    stringResource(R.string.usage_resets, it),
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
