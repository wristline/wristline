package dev.wristline.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.ListHeaderDefaults
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
import dev.wristline.watch.data.Item
import dev.wristline.watch.data.ItemKind
import dev.wristline.watch.data.Sent
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.SessionItems
import dev.wristline.watch.data.SessionStatus
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val COLLAPSED_LINES = 6
private const val SENT_NOTICE_MS = 4_000L

/** Holds the session open (subscription + items) exactly as long as the screen is composed. */
private class OpenedSession(val id: String) : RememberObserver {
    val items = Bridge.openSession(id)

    override fun onRemembered() = Unit

    override fun onForgotten() = Bridge.closeSession(id)

    override fun onAbandoned() = Bridge.closeSession(id)
}

@Composable
internal fun SessionDetailScreen(sessionId: String, onRespond: (String) -> Unit) {
    val opened = remember(sessionId) { OpenedSession(sessionId) }
    val items by opened.items.collectAsStateWithLifecycle()
    val sessions by Bridge.sessions.collectAsStateWithLifecycle()
    val requests by Bridge.requests.collectAsStateWithLifecycle()
    val conn by Bridge.conn.collectAsStateWithLifecycle()
    val session = remember(sessions, sessionId) { sessions.firstOrNull { it.id == sessionId } }
    val request = remember(requests, sessionId) { requests.firstOrNull { it.sessionId == sessionId } }

    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<Sent?>(null) }
    // Phase 5 puts speech recognition in front of this; the confirm-and-send path stays the same.
    val compose = rememberTextInput(stringResource(R.string.detail_prompt_label)) {
        draft = it
        outcome = null
        confirming = true
    }
    LaunchedEffect(outcome) {
        if (outcome == Sent.Ok) {
            delay(SENT_NOTICE_MS)
            outcome = null
        }
    }

    SessionDetailContent(
        session = session,
        gone = session == null && (conn is Conn.Online || conn is Conn.Demo),
        state = items,
        hasRequest = request != null,
        sending = sending,
        outcome = outcome,
        onEarlier = { Bridge.loadEarlier(sessionId) },
        onAction = { if (request != null) onRespond(request.id) else compose() },
    )

    AlertDialog(
        visible = confirming,
        onDismissRequest = { confirming = false },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    confirming = false
                    sending = true
                    scope.launch {
                        outcome = Bridge.prompt(sessionId, draft)
                        sending = false
                    }
                },
            )
        },
        title = { Text(stringResource(R.string.detail_confirm_title)) },
        text = { Text(draft, maxLines = COLLAPSED_LINES, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
internal fun SessionDetailContent(
    session: Session?,
    gone: Boolean,
    state: SessionItems,
    hasRequest: Boolean,
    sending: Boolean,
    outcome: Sent?,
    onEarlier: () -> Unit,
    onAction: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }
    val items = state.items
    val colors = MaterialTheme.colorScheme

    val showEarlier = state.canLoadEarlier
    val showLoading = items.isEmpty() && state.loading
    val showEmpty = items.isEmpty() && state.loaded && !state.loading
    val working = session?.status == SessionStatus.RUNNING
    val blockCode = session?.promptBlock?.takeIf { !hasRequest }
    val footers = listOf(working, blockCode != null, outcome != null).count { it }
    val lastIndex = 1 + listOf(showEarlier, showLoading, showEmpty).count { it } + items.size + footers - 1

    // Follow new items only when the user was at the bottom. Read during composition on purpose:
    // it flips only at the end of the list, and when a new item arrives it still describes the
    // layout from before that item, which is exactly "was the user at the bottom".
    val atBottom = !listState.canScrollForward
    val lastSeq = items.lastOrNull()?.seq
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(lastSeq, footers) {
        if (lastSeq == null || !atBottom) return@LaunchedEffect
        if (placed) {
            listState.animateScrollToItem(lastIndex)
        } else {
            // First page: start at the bottom without animating.
            listState.scrollToItem(lastIndex)
            placed = true
        }
    }

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(
                onClick = onAction,
                buttonSize = EdgeButtonSize.Medium,
                enabled = hasRequest || (session != null && session.promptBlock == null && !sending),
                colors = if (hasRequest) {
                    ButtonDefaults.buttonColors(containerColor = colors.tertiary, contentColor = colors.onTertiary)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                if (sending) {
                    SmallSpinner()
                } else {
                    Text(stringResource(if (hasRequest) R.string.detail_respond else R.string.detail_speak))
                }
            }
        },
    ) { contentPadding ->
        // Default rotary behaviour (fling with haptics): long messages are read continuously, not item by item.
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item(key = "header") {
                DetailHeader(
                    session = session,
                    gone = gone,
                    modifier = Modifier
                        .edgeTransform(this, spec)
                        .minimumVerticalContentPadding(ListHeaderDefaults.minimumTopListContentPadding),
                )
            }
            if (showEarlier) {
                item(key = "earlier") {
                    CompactButton(
                        onClick = onEarlier,
                        enabled = !state.loading,
                        modifier = Modifier.transformedHeight(this, spec).animateItem(),
                        transformation = SurfaceTransformation(spec),
                        label = { if (state.loading) SmallSpinner() else Text(stringResource(R.string.detail_earlier)) },
                    )
                }
            }
            if (showLoading) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().edgeTransform(this, spec), contentAlignment = Alignment.Center) { SmallSpinner() }
                }
            }
            if (showEmpty) {
                item(key = "empty") { CaptionText(stringResource(R.string.detail_empty), Modifier.edgeTransform(this, spec)) }
            }
            items(items, key = { it.seq }, contentType = { it.kind }) { item ->
                ItemRow(
                    item = item,
                    expanded = expanded[item.seq] == true,
                    onToggle = { expanded[item.seq] = expanded[item.seq] != true },
                    spec = spec,
                )
            }
            if (working) {
                item(key = "working") {
                    CaptionText(stringResource(R.string.detail_working), Modifier.edgeTransform(this, spec).animateItem())
                }
            }
            if (blockCode != null) {
                item(key = "block") { CaptionText(errorMessage(blockCode), Modifier.edgeTransform(this, spec)) }
            }
            if (outcome != null) {
                item(key = "outcome") {
                    val text = when (outcome) {
                        Sent.Ok -> stringResource(R.string.detail_sent)
                        Sent.Unreachable -> stringResource(R.string.error_unreachable)
                        is Sent.Refused -> errorMessage(outcome.code)
                    }
                    CaptionText(
                        text,
                        Modifier.edgeTransform(this, spec).animateItem(),
                        color = if (outcome == Sent.Ok) colors.primary else colors.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(session: Session?, gone: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (session != null) {
            Text(
                sessionTitle(session),
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(session.status)
                Text(providerLabel(session.provider), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                val context = session.context
                if (context != null && context.window > 0) {
                    val percent = context.used * 100.0 / context.window
                    PercentRing(percent, Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.detail_context, percent.roundToInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        } else if (gone) {
            CaptionText(stringResource(R.string.detail_gone))
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.ItemRow(
    item: Item,
    expanded: Boolean,
    onToggle: () -> Unit,
    spec: TransformationSpec,
) {
    val colors = MaterialTheme.colorScheme
    when (item.kind) {
        ItemKind.USER, ItemKind.ASSISTANT -> {
            // An expanded message can be far taller than the screen, and the edge transformation
            // renders its item through an offscreen layer of the full size; skip it there.
            Card(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth().then(if (expanded) Modifier else Modifier.transformedHeight(this, spec)),
                transformation = if (expanded) null else SurfaceTransformation(spec),
                colors = if (item.kind == ItemKind.USER) {
                    CardDefaults.cardColors(containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer)
                } else {
                    CardDefaults.cardColors()
                },
            ) {
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ItemKind.TOOL -> Card(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
            transformation = SurfaceTransformation(spec),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (item.pending) SmallSpinner()
                Text(
                    item.text,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (item.error) colors.error else Color.Unspecified,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val detail = item.detail
            if (expanded && !detail.isNullOrEmpty()) {
                Text(
                    detail,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyExtraSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (item.error) colors.error else colors.onSurfaceVariant,
                )
            }
        }
        else -> CaptionText(item.text, Modifier.edgeTransform(this, spec))
    }
}
