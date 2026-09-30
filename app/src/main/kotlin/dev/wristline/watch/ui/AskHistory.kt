package dev.wristline.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextDefaults
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import dev.wristline.watch.R
import dev.wristline.watch.data.Ask
import dev.wristline.watch.data.AskStatus
import dev.wristline.watch.data.Bridge

@Composable
internal fun AskHistoryScreen(onAsk: (String) -> Unit) {
    val asks by Bridge.asks.collectAsStateWithLifecycle()
    val now = rememberNowState()
    AskHistoryContent(asks, now = { now.value }, onAsk = onAsk)
}

/** This device's recent questions, newest first: the question, the provider and the answer's first line. */
@Composable
internal fun AskHistoryContent(asks: List<Ask>, now: () -> Long, onAsk: (String) -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val bottom = TextDefaults.minimumBottomListContentPadding
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
                ) { Text(stringResource(R.string.ask_history_title)) }
            }
            if (asks.isEmpty()) {
                item(key = "empty") {
                    BodyText(
                        stringResource(R.string.ask_history_empty),
                        Modifier.edgeTransform(this, spec).minimumVerticalContentPadding(top = 0.dp, bottom = bottom),
                    )
                }
            }
            items(asks, key = { it.id }) { ask ->
                TitleCard(
                    onClick = { onAsk(ask.id) },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).animateItem(),
                    transformation = SurfaceTransformation(spec),
                    colors = CardDefaults.cardColors(subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    title = {
                        Text(
                            ask.question.ifBlank { "…" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    time = {
                        val at = now()
                        val ago = remember(ask.createdAt, at) {
                            if (isJustNow(ask.createdAt, at)) null else relativeTime(ask.createdAt, at)
                        }
                        Text(ago ?: stringResource(R.string.time_just_now), maxLines = 1)
                    },
                    subtitle = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProviderBadge(ask.provider)
                            Text(askSummary(ask), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                )
            }
        }
    }
}

/** The answer's first line, or the status while running or failed. */
@Composable
private fun askSummary(ask: Ask): String = when (ask.status) {
    AskStatus.DONE -> ask.answer.orEmpty().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    AskStatus.RUNNING -> stringResource(R.string.ask_status_running)
    else -> stringResource(R.string.ask_status_error)
}
