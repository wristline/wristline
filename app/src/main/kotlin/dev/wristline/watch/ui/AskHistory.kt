package dev.wristline.watch.ui

import android.widget.Toast
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.Icon
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
import dev.wristline.watch.data.Sent
import dev.wristline.watch.data.isoToMillis
import dev.wristline.watch.data.thread
import kotlinx.coroutines.launch

/** One conversation on the history screen: its first question, its newest ask and how many it holds. */
internal class AskThread(val id: String, val first: Ask, val newest: Ask, val count: Int)

/**
 * The conversations in [asks] (newest first), each newest activity first. Among equal times the
 * later-listed ask is the older one.
 */
internal fun askThreads(asks: List<Ask>): List<AskThread> =
    asks.asReversed()
        .groupBy { it.thread }
        .map { (id, group) ->
            val ordered = group.sortedBy { isoToMillis(it.createdAt) ?: 0L }
            AskThread(id, ordered.first(), ordered.last(), group.size)
        }
        .sortedByDescending { isoToMillis(it.newest.createdAt) ?: 0L }

@Composable
internal fun AskHistoryScreen(onAsk: (String) -> Unit) {
    val asks by Bridge.asks.collectAsStateWithLifecycle()
    val now = rememberNowState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val code = error
    if (code != null) {
        val message = if (code == "unreachable") stringResource(R.string.error_unreachable) else errorMessage(code)
        LaunchedEffect(code) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            error = null
        }
    }
    AskHistoryContent(
        asks,
        now = { now.value },
        onAsk = onAsk,
        onDelete = { threadId ->
            scope.launch {
                when (val sent = Bridge.deleteThread(threadId)) {
                    Sent.Ok -> Unit
                    is Sent.Refused -> error = sent.code
                    Sent.Unreachable -> error = "unreachable"
                }
            }
        },
    )
}

/**
 * This device's recent conversations, newest first: the first question, the provider and how many
 * questions, or a lone question's answer. A tap opens the conversation; a long press offers to
 * delete it, on the bridge too.
 */
@Composable
internal fun AskHistoryContent(asks: List<Ask>, now: () -> Long, onAsk: (String) -> Unit, onDelete: (String) -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val bottom = TextDefaults.minimumBottomListContentPadding
    val threads = remember(asks) { askThreads(asks) }
    var deleting by remember { mutableStateOf<String?>(null) }
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
            if (threads.isEmpty()) {
                item(key = "empty") {
                    BodyText(
                        stringResource(R.string.ask_history_empty),
                        Modifier.edgeTransform(this, spec).minimumVerticalContentPadding(top = 0.dp, bottom = bottom),
                    )
                }
            }
            items(threads, key = { it.id }) { thread ->
                val interaction = remember { MutableInteractionSource() }
                val depth = rememberPressDepth(interaction)
                TitleCard(
                    onClick = { onAsk(thread.id) },
                    onLongClick = { deleting = thread.id },
                    onLongClickLabel = stringResource(R.string.ask_thread_delete_label),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).animateItemCalmly(this).pressScale(depth),
                    transformation = SurfaceTransformation(spec),
                    colors = CardDefaults.cardColors(subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    interactionSource = interaction,
                    title = {
                        Text(
                            thread.first.question.ifBlank { "…" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    time = { AgoText(thread.newest.createdAt, now) },
                    subtitle = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProviderBadge(thread.first.provider)
                            val summary = if (thread.count > 1) {
                                pluralStringResource(R.plurals.ask_thread_count, thread.count, thread.count)
                            } else {
                                askSummary(thread.newest)
                            }
                            Text(summary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                )
            }
        }
    }
    AlertDialog(
        visible = deleting != null,
        onDismissRequest = { deleting = null },
        icon = { Icon(painterResource(R.drawable.ic_delete), null) },
        title = { Text(stringResource(R.string.ask_thread_delete)) },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    deleting?.let(onDelete)
                    deleting = null
                },
            )
        },
    )
}

/** The answer's first line, or the status while running or failed. */
@Composable
private fun askSummary(ask: Ask): String = when (ask.status) {
    AskStatus.DONE -> ask.answer.orEmpty().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    AskStatus.RUNNING -> stringResource(R.string.ask_status_running)
    else -> stringResource(R.string.ask_status_error)
}
