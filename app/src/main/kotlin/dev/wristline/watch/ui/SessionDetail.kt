package dev.wristline.watch.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.weight
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
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.TimeTextDefaults
import androidx.wear.compose.material3.curvedText
import androidx.wear.compose.material3.timeTextSeparator
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
import dev.wristline.watch.data.isoToMillis
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val COLLAPSED_LINES = 6
// Far more than COLLAPSED_LINES hold: a collapsed message lays out this much of its up to 4000 chars.
private const val COLLAPSED_CHARS = 1_000
private const val SENT_NOTICE_MS = 4_000L
private val ACTION_SIZE = 44.dp
private val ACTION_GAP = 10.dp
// Low in the round screen's bottom chin, the two buttons still inside the circle.
private val ACTIONS_BOTTOM = 6.dp
// The list's end padding as a share of the screen height: at rest the newest short card ends about
// 70% down the screen, a little above the actions.
private const val END_PADDING_FRACTION = 0.3f
// Behind the actions, rising well above them: content scrolling under them fades out.
private val SCRIM_HEIGHT = 72.dp

// The curved top text (model and effort, in the time text's place) at its widest, centered on 12
// o'clock (270 degrees clockwise from 3 o'clock); its background's round ends add about 5 degrees
// on each side, so it spans at most about 234 to 306 degrees.
private const val TIME_TEXT_SWEEP = 62f
// Left gauge on the upper flank, in degrees clockwise from 3 o'clock; the right gauge mirrors it
// (312 to 340). Both stay about 6 degrees (10dp at the edge) clear of the widest time text, and the
// right one clear of the scroll indicator at 3 o'clock.
private const val GAUGE_START = 200f
private const val GAUGE_SWEEP = 28f
// No thinner than the scroll indicator (5dp, 6dp on screens 225dp and wider).
private val GAUGE_STROKE = 6.dp
// How long the gauges stay after scrolling stops, as the scroll indicator does.
private const val GAUGE_HOLD_MS = 1_200L

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
    val live = remember(sessions, sessionId) { sessions.firstOrNull { it.id == sessionId } }
    // A session that ends leaves the list, but the bridge still serves its transcript: it stays
    // readable as last listed, ended, and blocked as the bridge would block a prompt to it.
    var lastLive by remember(sessionId) { mutableStateOf<Session?>(null) }
    SideEffect { if (live != null) lastLive = live }
    val session = live ?: lastLive?.copy(status = SessionStatus.ENDED, promptBlock = "not_live")
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
    val footers = listOf(working, blockCode != null, outcome != null).count { it }
    val lastIndex = 1 + listOf(showEarlier, showLoading, showFailed, showEmpty).count { it } + items.size + footers - 1
    val screenHeight = LocalWindowInfo.current.containerSize.height
    val endPadding = with(LocalDensity.current) { (screenHeight * END_PADDING_FRACTION).toDp() }

    // Follow new items only when the user was at the bottom. Read during composition on purpose:
    // it flips only at the end of the list, and when a new item arrives it still describes the
    // layout from before that item, which is exactly "was the user at the bottom".
    val atBottom = !listState.canScrollForward
    val lastSeq = items.lastOrNull()?.seq
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(lastSeq, footers) {
        if (lastSeq == null || !atBottom) return@LaunchedEffect
        // Scrolling to an item centers it, which leaves a tall last card short of the end; lifted
        // a screen further it overshoots, and the list pins its end instead.
        if (placed) {
            listState.animateScrollToItem(lastIndex, screenHeight)
        } else {
            // First page: start at the bottom without animating.
            listState.scrollToItem(lastIndex, screenHeight)
            placed = true
        }
    }

    // No EdgeButton: the actions are small and fixed in the screen's bottom chin, and the list's
    // end padding keeps the newest card above them.
    ScreenScaffold(scrollState = listState, timeText = { DetailTopText(session) }) { contentPadding ->
        val layoutDirection = LocalLayoutDirection.current
        // Default rotary behaviour (fling with haptics): long messages are read continuously, not item by item.
        TransformingLazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(layoutDirection),
                top = contentPadding.calculateTopPadding(),
                end = contentPadding.calculateEndPadding(layoutDirection),
                bottom = endPadding,
            ),
        ) {
            item(key = "header") {
                DetailHeader(
                    session = session,
                    limit = limit,
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
        }
        // The content is a Box: what is composed after the list is drawn over it. A card scrolling
        // under the gauges is covered there while they show.
        if (session != null) EdgeGauges(listState, session.context, limit)
        // The screen's background at the very bottom, clear at its top. Drawn before the actions, so
        // it does not dim them; with no pointer input, touches on it reach the list.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(SCRIM_HEIGHT)
                .background(Brush.verticalGradient(listOf(Color.Transparent, colors.background))),
        )
        // [Respond] while a request waits, otherwise the speak and type buttons (disabled when blocked).
        val actions = Modifier.align(Alignment.BottomCenter).padding(bottom = ACTIONS_BOTTOM)
        if (hasRequest) {
            CompactButton(
                onClick = onAction,
                modifier = actions,
                colors = ButtonDefaults.buttonColors(containerColor = colors.tertiary, contentColor = colors.onTertiary),
                label = { Text(stringResource(R.string.detail_respond)) },
            )
        } else if (session != null) {
            Row(actions, horizontalArrangement = Arrangement.spacedBy(ACTION_GAP)) {
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
internal fun windowShort(window: UsageWindow): String {
    val minutes = window.minutes?.takeIf { it > 0 } ?: return window.id
    return when {
        minutes % 1_440 == 0 -> "${minutes / 1_440}d"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes}m"
    }
}

/** Context use in percent, or null when unknown. */
private fun contextPercent(context: ContextUsage?): Double? =
    context?.takeIf { it.window > 0 }?.let { it.used * 100.0 / it.window }

/** The limit window's color: tertiary, or the error color near the limit. */
@Composable
private fun limitColor(percent: Int): Color =
    if (percent >= NEAR_LIMIT_PERCENT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary

/**
 * Context use (left) and the limit window (right) as bare arcs on the upper flanks; the header
 * spells out their numbers. Clear of the time text and the scroll indicator; both fill upwards,
 * towards the time. They show at the top of the list, next to the header, and like the scroll
 * indicator while it scrolls and for [GAUGE_HOLD_MS] after; at rest elsewhere they fade out.
 */
@Composable
private fun EdgeGauges(listState: TransformingLazyColumnState, context: ContextUsage?, limit: UsageWindow?) {
    // Scrolling, or scrolled within the last GAUGE_HOLD_MS.
    var scrolled by remember(listState) { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling ->
            if (!scrolling) delay(GAUGE_HOLD_MS)
            scrolled = scrolling
        }
    }
    val visible by remember(listState) {
        derivedStateOf { scrolled || listState.layoutInfo.visibleItems.firstOrNull()?.index == 0 }
    }
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        contextPercent(context)?.let { percent ->
            EdgeGauge(percent, MaterialTheme.colorScheme.primary, right = false)
        }
        if (limit != null) {
            val percent = limit.usedPercent.roundToInt()
            EdgeGauge(limit.usedPercent, limitColor(percent), right = true)
        }
    }
}

@Composable
private fun EdgeGauge(percent: Double, color: Color, right: Boolean) {
    // As in PercentRing: the indicator observes only the State its first progress lambda reads.
    val progress by rememberUpdatedState((percent / 100).toFloat().coerceIn(0f, 1f))
    val edge = CircularProgressIndicatorDefaults.FullScreenPadding
    CircularProgressIndicator(
        progress = { progress },
        // The right gauge is the left one mirrored, so it too fills upwards.
        modifier = Modifier
            .fillMaxSize()
            .padding(edge)
            .graphicsLayer { if (right) scaleX = -1f }
            // Silent: the header reads the numbers, and a full-screen node over the list would hide
            // everything under it from accessibility services.
            .clearAndSetSemantics {},
        startAngle = GAUGE_START,
        endAngle = GAUGE_START + GAUGE_SWEEP,
        // A track plainly visible on the black, well short of the fill. The ends are round.
        colors = ProgressIndicatorDefaults.colors(
            indicatorColor = color,
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
        ),
        strokeWidth = GAUGE_STROKE,
    )
}

/**
 * The session's model and effort, `Fable 5.1 · high`, curved at the top in the time text's place
 * (no clock); the model is cut short when they do not fit. Its provider without either, nothing
 * without a [session].
 */
@Composable
private fun DetailTopText(session: Session?) {
    if (session == null) return
    val model = session.model
    val effort = session.effort
    val provider = providerLabel(session.provider)
    val style = TimeTextDefaults.timeTextStyle()
    TimeText(maxSweepAngle = TIME_TEXT_SWEEP) {
        if (model == null && effort == null) {
            curvedText(provider, overflow = TextOverflow.Ellipsis, style = style)
            return@TimeText
        }
        // Weighted, the model takes what the effort and separator leave.
        if (model != null) curvedText(model, CurvedModifier.weight(1f), overflow = TextOverflow.Ellipsis, style = style)
        if (model != null && effort != null) timeTextSeparator()
        if (effort != null) curvedText(effort, style = style)
    }
}

@Composable
private fun DetailHeader(session: Session?, limit: UsageWindow?, gone: Boolean, modifier: Modifier) {
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
                ProviderBadge(session.provider)
                StatusDot(session.status)
                Text(
                    model.ifEmpty { providerLabel(session.provider) },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            GaugeNumbers(session.context, limit)
        } else if (gone) {
            CaptionText(stringResource(R.string.detail_gone))
        }
    }
}

/**
 * The edge gauges' numbers, `ctx 46% · 5h 6%`: muted labels, each number in its arc's color.
 * Nothing when neither is known.
 */
@Composable
private fun GaugeNumbers(context: ContextUsage?, limit: UsageWindow?) {
    val contextShown = contextPercent(context)?.roundToInt()
    val limitShown = limit?.usedPercent?.roundToInt()
    if (contextShown == null && limitShown == null) return
    val contextLabel = stringResource(R.string.detail_context)
    val contextTint = MaterialTheme.colorScheme.primary
    val limitTint = limitShown?.let { limitColor(it) } ?: Color.Unspecified
    val spoken = listOfNotNull(
        contextShown?.let { stringResource(R.string.detail_context_description, it) },
        limit?.let { "${windowLabel(it)} $limitShown%" },
    ).joinToString(", ")
    val text = buildAnnotatedString {
        if (contextShown != null) {
            append("$contextLabel ")
            withStyle(SpanStyle(color = contextTint)) { append("$contextShown%") }
        }
        if (limit != null) {
            if (contextShown != null) append(" · ")
            append("${windowShort(limit)} ")
            withStyle(SpanStyle(color = limitTint)) { append("$limitShown%") }
        }
    }
    Text(
        text,
        modifier = Modifier.padding(horizontal = 24.dp).clearAndSetSemantics { contentDescription = spoken },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * An item's time in the locale's short form (`4:52 PM`, `오후 4:52`), after its `M/d` date when it
 * is not from [today]; null when [iso] does not parse.
 */
internal fun itemTime(
    iso: String,
    locale: Locale,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): String? {
    val millis = isoToMillis(iso) ?: return null
    val time = Instant.ofEpochMilli(millis).atZone(zone)
    val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(time)
    if (time.toLocalDate() == today) return clock
    return DateTimeFormatter.ofPattern("M/d", locale).format(time) + " " + clock
}

/** An item's time as a small muted caption; formatted once per item, nothing when it does not parse. */
@Composable
private fun ItemTime(ts: String) {
    val locale = LocalConfiguration.current.locales[0]
    val time = remember(ts, locale) { itemTime(ts, locale) } ?: return
    Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
}

/** The start of a long message, enough to fill the collapsed lines and end in an ellipsis. */
private fun collapsedText(text: String): String {
    if (text.length <= COLLAPSED_CHARS) return text
    // Never split a surrogate pair.
    return text.substring(0, if (text[COLLAPSED_CHARS - 1].isHighSurrogate()) COLLAPSED_CHARS - 1 else COLLAPSED_CHARS)
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
                ItemTime(item.ts)
                Text(
                    if (expanded) item.text else collapsedText(item.text),
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
        else -> Column(
            Modifier.fillMaxWidth().edgeTransform(this, spec).then(appear),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ItemTime(item.ts)
            CaptionText(item.text)
        }
    }
}
