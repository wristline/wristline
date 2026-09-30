package dev.wristline.watch.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.CurvedDirection
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CircularProgressIndicatorDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.curvedText
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import dev.wristline.watch.R
import dev.wristline.watch.data.Bridge
import dev.wristline.watch.data.Conn
import dev.wristline.watch.data.ContextUsage
import dev.wristline.watch.data.Item
import dev.wristline.watch.data.ItemKind
import dev.wristline.watch.data.ProviderId
import dev.wristline.watch.data.Sent
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.SessionItems
import dev.wristline.watch.data.SessionStatus
import dev.wristline.watch.data.Usage
import dev.wristline.watch.data.UsageWindow
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val COLLAPSED_LINES = 6
private const val SENT_NOTICE_MS = 4_000L
private val ACTION_SIZE = 44.dp

// Left gauge, in degrees clockwise from 3 o'clock; the right gauge mirrors it.
private const val GAUGE_START = 120f
private const val GAUGE_SWEEP = 40f
private val GAUGE_STROKE = 4.dp

/** Holds the session open (its items) exactly as long as the screen is composed. */
private class OpenedSession(val id: String) : RememberObserver {
    val items = Bridge.openSession(id)

    override fun onRemembered() = Unit

    override fun onForgotten() = Bridge.closeSession(id)

    override fun onAbandoned() = Bridge.closeSession(id)
}

@Composable
internal fun SessionDetailScreen(sessionId: String, onRespond: (String) -> Unit) {
    val opened = remember(sessionId) { OpenedSession(sessionId) }
    // Live items only while started: with the screen off, or another activity in front, nothing
    // streams; coming back reloads the newest page.
    LifecycleStartEffect(sessionId) {
        Bridge.watchSession(sessionId)
        onStopOrDispose { Bridge.unwatchSession(sessionId) }
    }
    val items by opened.items.collectAsStateWithLifecycle()
    val sessions by Bridge.sessions.collectAsStateWithLifecycle()
    val requests by Bridge.requests.collectAsStateWithLifecycle()
    val conn by Bridge.conn.collectAsStateWithLifecycle()
    val usage by Bridge.usage.collectAsStateWithLifecycle()
    val session = remember(sessions, sessionId) { sessions.firstOrNull { it.id == sessionId } }
    val limit = remember(session, usage) { session?.let { sessionLimit(it, usage) } }
    val request = remember(requests, sessionId) { requests.firstOrNull { it.sessionId == sessionId } }

    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    // How the draft was entered; [Retry] asks the same way again.
    var typed by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<Sent?>(null) }
    val label = stringResource(R.string.detail_prompt_label)
    val context = LocalContext.current
    val tryType = rememberTextInputLauncher(label) {
        draft = it
        typed = true
        outcome = null
        confirming = true
    }
    val trySpeak = rememberSpeechInput(label) {
        draft = it
        typed = false
        outcome = null
        confirming = true
    }
    // Each way in falls back to the other; a watch with neither is told so.
    val type = remember(tryType, trySpeak) { { if (!tryType() && !trySpeak()) inputUnavailable(context) } }
    val speak = remember(tryType, trySpeak) { { if (!trySpeak() && !tryType()) inputUnavailable(context) } }
    LaunchedEffect(outcome) {
        if (outcome == Sent.Ok) {
            delay(SENT_NOTICE_MS)
            outcome = null
        }
    }

    SessionDetailContent(
        session = session,
        gone = session == null && (conn is Conn.Online || conn is Conn.Demo),
        limit = limit,
        state = items,
        hasRequest = request != null,
        sending = sending,
        outcome = outcome,
        onEarlier = { Bridge.loadEarlier(sessionId) },
        onAction = { if (request != null) onRespond(request.id) else speak() },
        onType = type,
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
        // Not clipped: a long message scrolls with the dialog.
        text = { Text(draft) },
    ) {
        item {
            FilledTonalButton(
                onClick = {
                    confirming = false
                    if (typed) type() else speak()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.detail_retry)) },
            )
        }
    }
}

/**
 * Returns a launcher for the system speech recognizer (free-form, device language, one result).
 * [onText] gets the trimmed, non-empty transcript. Returns false when the watch has no recognizer
 * (or will not let this app start it), so the caller can offer typing instead.
 */
@Composable
private fun rememberSpeechInput(prompt: String, onText: (String) -> Unit): () -> Boolean {
    val latest by rememberUpdatedState(onText)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!text.isNullOrEmpty()) latest(text)
    }
    return remember(launcher, prompt) {
        {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            try {
                launcher.launch(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
    }
}

@Composable
internal fun SessionDetailContent(
    session: Session?,
    gone: Boolean,
    limit: UsageWindow?,
    state: SessionItems,
    hasRequest: Boolean,
    sending: Boolean,
    outcome: Sent?,
    onEarlier: () -> Unit,
    onAction: () -> Unit,
    onType: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }
    val items = state.items
    val colors = MaterialTheme.colorScheme

    val showEarlier = state.canLoadEarlier
    val showLoading = items.isEmpty() && state.loading
    val showFailed = items.isEmpty() && state.failed && !state.loading
    val showEmpty = items.isEmpty() && state.loaded && !state.loading && !state.failed
    val working = session?.status == SessionStatus.RUNNING
    val blockCode = session?.promptBlock?.takeIf { !hasRequest }
    val canSend = session != null && session.promptBlock == null && !sending
    // [Respond] while a request waits, otherwise the speak and type buttons (disabled when blocked).
    val hasActions = hasRequest || session != null
    val footers = listOf(working, blockCode != null, outcome != null, hasActions).count { it }
    val lastIndex = 1 + listOf(showEarlier, showLoading, showFailed, showEmpty).count { it } + items.size + footers - 1

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

    // No EdgeButton: the actions are small and last in the list, so the transcript keeps the screen.
    ScreenScaffold(scrollState = listState) { contentPadding ->
        // The content is a Box: the gauges composed first are drawn under the list.
        if (session != null) EdgeGauges(session.context, limit)
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
            if (showFailed) {
                item(key = "failed") {
                    CaptionText(stringResource(R.string.detail_load_failed), Modifier.edgeTransform(this, spec), color = colors.error)
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
            if (hasRequest) {
                item(key = "respond") {
                    CompactButton(
                        onClick = onAction,
                        modifier = Modifier
                            .transformedHeight(this, spec)
                            .animateItem()
                            .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding),
                        transformation = SurfaceTransformation(spec),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.tertiary, contentColor = colors.onTertiary),
                        label = { Text(stringResource(R.string.detail_respond)) },
                    )
                }
            } else if (session != null) {
                item(key = "actions") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .edgeTransform(this, spec)
                            .animateItem()
                            .minimumVerticalContentPadding(IconButtonDefaults.minimumVerticalListContentPadding),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    ) {
                        FilledIconButton(onClick = onAction, enabled = canSend, modifier = Modifier.size(ACTION_SIZE)) {
                            if (sending) {
                                SmallSpinner()
                            } else {
                                Icon(painterResource(R.drawable.ic_mic), stringResource(R.string.detail_speak))
                            }
                        }
                        FilledTonalIconButton(onClick = onType, enabled = canSend, modifier = Modifier.size(ACTION_SIZE)) {
                            Icon(painterResource(R.drawable.ic_keyboard), stringResource(R.string.detail_type))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The window a session's gauge shows: the 5-hour window (`5h`) of the usage entry under the
 * session's account for Claude Code, the primary window for other providers. A session without a
 * matching account entry falls back to its provider's only entry; null when there is none or the
 * choice would be a guess.
 */
internal fun sessionLimit(session: Session, usage: List<Usage>): UsageWindow? {
    val entries = usage.filter { it.provider == session.provider }
    val entry = entries.firstOrNull { it.account?.id == session.account?.id } ?: entries.singleOrNull() ?: return null
    return if (session.provider == ProviderId.CLAUDE_CODE) {
        entry.windows.firstOrNull { it.id == "5h" }
    } else {
        entry.windows.firstOrNull { it.id == "primary" } ?: entry.windows.firstOrNull()
    }
}

/** `5h`, `7d`: a window's length in its shortest form, or its id when the length is unknown. */
private fun windowShort(window: UsageWindow): String {
    val minutes = window.minutes?.takeIf { it > 0 } ?: return window.id
    return when {
        minutes % 1_440 == 0 -> "${minutes / 1_440}d"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes}m"
    }
}

/**
 * Context use (left) and the limit window (right) as thin arcs on the lower edge, each with a
 * curved label inside it. Drawn under the list, clear of the time text, the scroll indicator and
 * the actions; both fill upwards.
 */
@Composable
private fun EdgeGauges(context: ContextUsage?, limit: UsageWindow?) {
    val colors = MaterialTheme.colorScheme
    if (context != null && context.window > 0) {
        val percent = context.used * 100.0 / context.window
        EdgeGauge(percent, stringResource(R.string.detail_context, percent.roundToInt()), colors.primary, right = false)
    }
    if (limit != null) {
        val percent = limit.usedPercent.roundToInt()
        val color = if (percent >= NEAR_LIMIT_PERCENT) colors.error else colors.tertiary
        EdgeGauge(limit.usedPercent, "${windowShort(limit)} $percent%", color, right = true)
    }
}

@Composable
private fun EdgeGauge(percent: Double, label: String, color: Color, right: Boolean) {
    // As in PercentRing: the indicator observes only the State its first progress lambda reads.
    val progress by rememberUpdatedState((percent / 100).toFloat().coerceIn(0f, 1f))
    val edge = CircularProgressIndicatorDefaults.FullScreenPadding
    CircularProgressIndicator(
        progress = { progress },
        // The right gauge is the left one mirrored, so it too fills from the bottom up.
        modifier = Modifier.fillMaxSize().padding(edge).graphicsLayer { if (right) scaleX = -1f },
        startAngle = GAUGE_START,
        endAngle = GAUGE_START + GAUGE_SWEEP,
        colors = ProgressIndicatorDefaults.colors(indicatorColor = color),
        strokeWidth = GAUGE_STROKE,
    )
    val middle = GAUGE_START + GAUGE_SWEEP / 2
    val style = MaterialTheme.typography.arcSmall
    CurvedLayout(
        Modifier.fillMaxSize().padding(edge + GAUGE_STROKE + 2.dp),
        anchor = if (right) 180f - middle else middle,
        angularDirection = CurvedDirection.Angular.CounterClockwise,
    ) {
        curvedText(label, color = color, style = style)
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
            // `Fable 5.1 · xhigh` names the provider too; without either, the provider alone.
            val model = listOfNotNull(session.model, session.effort).joinToString(" · ")
            Row(
                Modifier.padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(session.status)
                Text(
                    model.ifEmpty { providerLabel(session.provider) },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
    // New items fade in. No placement animation: the rows already follow a neighbour's animated
    // size exactly, and a placement spring would trail it.
    val appear = Modifier.animateItem(placementSpec = null)
    when (item.kind) {
        ItemKind.USER, ItemKind.ASSISTANT -> {
            // An expanded message can be far taller than the screen, and the edge transformation
            // renders its item through an offscreen layer of the full size; skip it there. (It is
            // back for the few frames a long message takes to shrink after collapsing.)
            Card(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth().then(if (expanded) Modifier else Modifier.transformedHeight(this, spec)).then(appear),
                transformation = if (expanded) null else SurfaceTransformation(spec),
                colors = if (item.kind == ItemKind.USER) {
                    CardDefaults.cardColors(containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer)
                } else {
                    CardDefaults.cardColors()
                },
            ) {
                Text(
                    item.text,
                    modifier = Modifier.animateContentSize(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ItemKind.TOOL -> Card(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).then(appear),
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
            if (!detail.isNullOrEmpty()) {
                AnimatedVisibility(visible = expanded) {
                    Text(
                        detail,
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyExtraSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (item.error) colors.error else colors.onSurfaceVariant,
                    )
                }
            }
        }
        else -> CaptionText(item.text, Modifier.edgeTransform(this, spec).then(appear))
    }
}
