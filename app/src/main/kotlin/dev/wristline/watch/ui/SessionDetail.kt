package dev.wristline.watch.ui

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.LocalReduceMotion
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
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
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.touchTargetAwareSize
import dev.wristline.watch.R
import dev.wristline.watch.data.Bridge
import dev.wristline.watch.data.Conn
import dev.wristline.watch.data.ContextUsage
import dev.wristline.watch.data.Item
import dev.wristline.watch.data.ItemKind
import dev.wristline.watch.data.LimitKind
import dev.wristline.watch.data.ProviderId
import dev.wristline.watch.data.Sent
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.SessionItems
import dev.wristline.watch.data.SessionStatus
import dev.wristline.watch.data.Usage
import dev.wristline.watch.data.UsageWindow
import dev.wristline.watch.data.isoToMillis
import dev.wristline.watch.data.sentHaptic
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val COLLAPSED_LINES = 6
// Far more than COLLAPSED_LINES hold: a collapsed message lays out this much of its up to 4000 chars.
private const val COLLAPSED_CHARS = 1_000
private const val SENT_NOTICE_MS = 4_000L
// How long the speak button shows a check after a prompt went out.
private const val SENT_CHECK_MS = 1_200L
// The library's small size, the minimum touch target.
private val ACTION_SIZE = IconButtonDefaults.SmallButtonSize
private val ACTION_GAP = 8.dp
// Low in the round screen's bottom chin, the two buttons still inside the circle (192dp screens too).
private val ACTIONS_BOTTOM = 6.dp
// The list's end padding as a share of the screen height: at rest the newest short card ends about
// 70% down the screen, a little above the actions.
private const val END_PADDING_FRACTION = 0.3f
// Behind the actions, rising well above them: content scrolling under them fades out.
private val SCRIM_HEIGHT = 72.dp
// The working indicator under the newest item: smaller than the usual spinner, low-key.
private val WORKING_SPINNER = 12.dp
// The page glyph beside a plan's time, the time's height.
private val PLAN_GLYPH = 12.dp
// The list's top padding: the header starts a little below the round screen's top.
private val LIST_TOP = 20.dp
// The list's side padding comes this much inside the default. No more: the round edge clips
// wider cards.
private val LIST_SIDE_TRIM = 2.dp

// Left gauge from GAUGE_START to GAUGE_START + GAUGE_SWEEP, in degrees clockwise from 3 o'clock:
// 170 to 202, above 9 o'clock but for its lower end; the right gauge mirrors it (338 to 10, at 3
// o'clock, where the scroll indicator is: it is hidden while the gauges show). Below each lower
// end, centered GAUGE_GLYPH_ANGLE on the same ring, the glyph saying what it measures, upright.
private const val GAUGE_START = 170f
private const val GAUGE_SWEEP = 32f
// Clears the arc's round end by a few dp.
private const val GAUGE_GLYPH_ANGLE = 163f
private val GAUGE_GLYPH = 14.dp
// The right gauge starts filling in this long after the left.
private const val GAUGE_STAGGER_MS = 120L
// No thinner than the scroll indicator (5dp, 6dp on screens 225dp and wider).
private val GAUGE_STROKE = 6.dp
// How long the list stays still before the gauges show.
private const val GAUGE_SETTLE_MS = 300L
// The list's first item: inset and centered, it sits between the gauges.
private const val HEADER_KEY = "header"
// What shows and hides as the list scrolls or a tool call opens: never overshooting.
private val CalmFadeIn = fadeIn(CalmMotion.defaultEffectsSpec())
private val CalmFadeOut = fadeOut(CalmMotion.defaultEffectsSpec())

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
    val touch = rememberTouchHaptics()
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
        usage = usage,
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
                        val sent = Bridge.prompt(sessionId, draft)
                        outcome = sent
                        sending = false
                        touch(sentHaptic(sent))
                    }
                },
            )
        },
        title = { Text(stringResource(R.string.detail_confirm_title)) },
        // Not clipped: a long message scrolls with the dialog.
        text = { DialogMessage(draft) },
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

@Composable
internal fun SessionDetailContent(
    session: Session?,
    gone: Boolean,
    limit: UsageWindow?,
    state: SessionItems,
    /** The usage entries, for when an error card's limit ends ([limitEnd]). */
    usage: List<Usage> = emptyList(),
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
    val screenHeight = LocalWindowInfo.current.containerSize.height
    val endPadding = with(LocalDensity.current) { (screenHeight * END_PADDING_FRACTION).toDp() }

    // Reverse layout (the chat and log pattern): index 0, the newest, is at the bottom, where the
    // list starts; the earlier loader and the header are at its far end. Follow new items only when
    // the user was at the bottom. Read during composition on purpose: it flips only at the end of
    // the list, and when a new item arrives it still describes the layout from before that item,
    // which is exactly "was the user at the bottom".
    val atBottom = !listState.canScrollBackward
    val lastSeq = items.lastOrNull()?.seq
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(lastSeq, footers) {
        if (lastSeq == null || !atBottom) return@LaunchedEffect
        if (placed) {
            // The list may hold the card the user saw in place, leaving the new one under the chin;
            // a screen toward the bottom stops at the end.
            listState.animateScrollBy(-screenHeight.toFloat())
        } else {
            // First page: the list held the header or the spinner in place as the items came in
            // under it, possibly screens away. Back to the newest without animating.
            listState.scrollToItem(0)
            listState.scrollBy(-screenHeight.toFloat())
            placed = true
        }
    }

    // Once a prompt went out, a check in the spinner's place for a moment.
    var sentCheck by remember(outcome) { mutableStateOf(outcome == Sent.Ok) }
    LaunchedEffect(outcome) {
        if (sentCheck) {
            delay(SENT_CHECK_MS)
            sentCheck = false
        }
    }

    // No EdgeButton: the actions are small and fixed in the screen's bottom chin, and the list's
    // end padding keeps the newest card above them.
    val gaugesShown by rememberGaugesShown(listState)
    // The gauges fill in from zero the first time they show, once per visit: held here, as they
    // leave composition whenever the list scrolls.
    var gaugesFilled by remember { mutableStateOf(false) }
    ScreenScaffold(
        scrollState = listState,
        // Nothing at the top, not even the clock: the session cards show the model and effort.
        timeText = {},
        // The scaffold keeps the indicator (at 3 o'clock, under the right gauge) for 2s after a
        // scroll, the gauges come back after GAUGE_SETTLE_MS: hidden while they show.
        scrollIndicator = {
            AnimatedVisibility(!gaugesShown, enter = CalmFadeIn, exit = CalmFadeOut) { ScrollIndicator(listState) }
        },
    ) { contentPadding ->
        val layoutDirection = LocalLayoutDirection.current
        // Default rotary behaviour (fling with haptics): long messages are read continuously, not item by item.
        TransformingLazyColumn(
            state = listState,
            reverseLayout = true,
            // A short transcript stays at the top under its header, as it would unreversed (the
            // reversed default is the bottom); 4dp as the default.
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Top),
            contentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(layoutDirection) - LIST_SIDE_TRIM,
                top = LIST_TOP,
                end = contentPadding.calculateEndPadding(layoutDirection) - LIST_SIDE_TRIM,
                bottom = endPadding,
            ),
        ) {
            if (outcome != null) {
                item(key = "outcome") {
                    val text = when (outcome) {
                        Sent.Ok -> stringResource(R.string.detail_sent)
                        Sent.Unreachable -> stringResource(R.string.error_unreachable)
                        is Sent.Refused -> errorMessage(outcome.code)
                    }
                    CaptionText(
                        text,
                        Modifier.edgeTransform(this, spec).animateItemCalmly(this).minListItemHeight().announced(),
                        color = if (outcome == Sent.Ok) colors.primary else colors.error,
                    )
                }
            }
            if (blockCode != null) {
                item(key = "block") { CaptionText(errorMessage(blockCode), Modifier.edgeTransform(this, spec).minListItemHeight()) }
            }
            if (working) {
                item(key = "working") {
                    // A small gray spinner, no words; TalkBack reads them.
                    val description = stringResource(R.string.detail_working)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .edgeTransform(this, spec)
                            .animateItemCalmly(this)
                            .minListItemHeight()
                            .clearAndSetSemantics { contentDescription = description },
                        contentAlignment = Alignment.Center,
                    ) { SmallSpinner(Modifier.size(WORKING_SPINNER), color = colors.onSurfaceVariant) }
                }
            }
            items(items.asReversed(), key = { it.seq }, contentType = { it.kind }) { item ->
                ItemRow(
                    item = item,
                    limitEnd = if (item.error) remember(item, session, usage) { limitEnd(item.limitKind, item.resetsAt, item.resetsEstimated, session, usage, System.currentTimeMillis()) } else null,
                    expanded = expanded[item.seq] == true,
                    onToggle = { expanded[item.seq] = expanded[item.seq] != true },
                    spec = spec,
                )
            }
            if (showEmpty) {
                item(key = "empty") { CaptionText(stringResource(R.string.detail_empty), Modifier.edgeTransform(this, spec).minListItemHeight()) }
            }
            if (showFailed) {
                item(key = "failed") {
                    CaptionText(stringResource(R.string.detail_load_failed), Modifier.edgeTransform(this, spec).minListItemHeight(), color = colors.error)
                }
            }
            if (showLoading) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().edgeTransform(this, spec), contentAlignment = Alignment.Center) { SmallSpinner() }
                }
            }
            if (showEarlier) {
                item(key = "earlier") {
                    CompactButton(
                        onClick = onEarlier,
                        enabled = !state.loading,
                        modifier = Modifier.transformedHeight(this, spec).animateItemCalmly(this),
                        // Secondary, so gray: the blue fill is the mic's.
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        transformation = SurfaceTransformation(spec),
                        label = { if (state.loading) SmallSpinner() else Text(stringResource(R.string.detail_earlier)) },
                    )
                }
            }
            item(key = HEADER_KEY) {
                DetailHeader(
                    session = session,
                    limit = limit,
                    gone = gone,
                    modifier = Modifier.edgeTransform(this, spec),
                )
            }
        }
        // The screen's background at the very bottom, clear at its top. Drawn before the gauges and
        // the actions, so it does not dim them; with no pointer input, touches on it reach the list.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(SCRIM_HEIGHT)
                .background(Brush.verticalGradient(listOf(Color.Transparent, colors.background))),
        )
        // The content is a Box: what is composed after the list is drawn over it. The arcs and
        // their glyphs show whenever the list is at rest.
        if (session != null) {
            EdgeGauges(gaugesShown, session.context, limit, fillIn = !gaugesFilled, onFillStarted = { gaugesFilled = true })
        }
        // [Respond] while a request waits, otherwise the speak and type buttons (disabled when blocked).
        val actions = Modifier.align(Alignment.BottomCenter).padding(bottom = ACTIONS_BOTTOM)
        if (hasRequest) {
            CompactButton(
                onClick = onAction,
                modifier = actions,
                // Fixed yellow, not the theme's tertiary: watch colors could make it any hue.
                colors = ButtonDefaults.buttonColors(containerColor = Status.Attention, contentColor = Color.Black),
                label = { Text(stringResource(R.string.detail_respond)) },
            )
        } else if (session != null) {
            Row(actions, horizontalArrangement = Arrangement.spacedBy(ACTION_GAP)) {
                // Round, squarer while pressed, but only so far: square corners this low would reach
                // past the round screen's edge. The same size, so the pair never shifts.
                val shapes = IconButtonDefaults.animatedShapes(pressedShape = MaterialTheme.shapes.medium)
                FilledIconButton(onClick = onAction, enabled = canSend, modifier = Modifier.touchTargetAwareSize(ACTION_SIZE), shapes = shapes) {
                    SpeakIcon(
                        when {
                            sending -> SpeakState.SENDING
                            sentCheck -> SpeakState.SENT
                            else -> SpeakState.MIC
                        },
                    )
                }
                FilledTonalIconButton(onClick = onType, enabled = canSend, modifier = Modifier.touchTargetAwareSize(ACTION_SIZE), shapes = shapes) {
                    Icon(painterResource(R.drawable.ic_keyboard), stringResource(R.string.detail_type))
                }
            }
        }
    }
}

private enum class SpeakState { MIC, SENDING, SENT }

/** The speak button's mic, its spinner while a prompt is sent, then a check that pops in. */
@Composable
private fun SpeakIcon(state: SpeakState) {
    val motion = MaterialTheme.motionScheme
    val reduceMotion = LocalReduceMotion.current
    AnimatedContent(
        targetState = state,
        transitionSpec = {
            val fade = fadeIn(motion.fastEffectsSpec())
            // No size change to animate: the button is fixed.
            (if (reduceMotion) fade else fade + scaleIn(motion.fastSpatialSpec(), initialScale = 0.6f))
                .togetherWith(fadeOut(motion.fastEffectsSpec()))
                .using(null)
        },
        contentAlignment = Alignment.Center,
        label = "speak",
    ) { shown ->
        when (shown) {
            SpeakState.SENDING -> SmallSpinner()
            SpeakState.SENT -> Icon(painterResource(R.drawable.ic_check), stringResource(R.string.detail_sent))
            SpeakState.MIC -> Icon(painterResource(R.drawable.ic_mic), stringResource(R.string.detail_speak))
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

/** When a usage limit ends, as its card and notification show it ([limitEnd]). */
internal sealed interface LimitEnd {
    /** At [millis]; [estimated] (shown with `~`) when inferred from a window near full rather than known. */
    data class At(val millis: Long, val estimated: Boolean) : LimitEnd

    /** The account's usage credits ran out, and no window is full: no reset time. */
    data object Credits : LimitEnd
}

/**
 * When the usage limit of an error item or `limit` alert ends: its [resetsAt] when the bridge sent
 * one ([estimated] as the bridge marks it). Else, for a usage limit ([limitKind] set), from the
 * windows of [session]'s usage entry in [usage] (as [sessionLimit] picks the entry) that reset after
 * [now]: the latest reset of those at 100% (the one that blocks; Codex reports a used-up weekly
 * window as credits running out too); else [LimitEnd.Credits] for a credits limit; else the reset of
 * the fullest window from [AT_LIMIT_PERCENT], estimated; else null rather than a misleading time.
 */
internal fun limitEnd(limitKind: String?, resetsAt: String?, estimated: Boolean, session: Session?, usage: List<Usage>, now: Long): LimitEnd? {
    isoToMillis(resetsAt)?.let { return LimitEnd.At(it, estimated) }
    if (limitKind == null) return null
    val entries = usage.filter { it.provider == session?.provider }
    val entry = entries.firstOrNull { it.account?.id == session?.account?.id } ?: entries.singleOrNull()
    val windows = entry?.windows.orEmpty().mapNotNull { w -> isoToMillis(w.resetsAt)?.takeIf { it > now }?.let { w.usedPercent to it } }
    windows.filter { it.first >= 100 }.maxOfOrNull { it.second }?.let { return LimitEnd.At(it, estimated = false) }
    if (limitKind == LimitKind.CREDITS) return LimitEnd.Credits
    return windows.filter { it.first >= AT_LIMIT_PERCENT }.maxByOrNull { it.first }?.let { LimitEnd.At(it.second, estimated = true) }
}

/** Context use in percent, or null when unknown. */
private fun contextPercent(context: ContextUsage?): Double? =
    context?.takeIf { it.window > 0 }?.let { it.used * 100.0 / it.window }

/**
 * A limit window's color for its rounded [percent], the same on the gauge, the rings and the list's
 * usage card: white, yellow from [NEAR_LIMIT_PERCENT], red from [AT_LIMIT_PERCENT].
 */
internal fun limitColor(percent: Int): Color = when {
    percent >= AT_LIMIT_PERCENT -> WristlineColors.error
    percent >= NEAR_LIMIT_PERCENT -> Status.Attention
    else -> WristlineColors.onSurface
}

/**
 * Whether the edge gauges show: only while the list is at rest (they fade out as soon as it scrolls
 * and back in [GAUGE_SETTLE_MS] after it stops), wherever it is scrolled to.
 */
@Composable
private fun rememberGaugesShown(listState: TransformingLazyColumnState): State<Boolean> {
    // Not scrolled for the last GAUGE_SETTLE_MS.
    val resting = remember(listState) { mutableStateOf(!listState.isScrollInProgress) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling ->
            if (!scrolling) delay(GAUGE_SETTLE_MS)
            resting.value = !scrolling
        }
    }
    return resting
}

/**
 * Context use (left) and the limit window (right) as bare arcs beside 9 and 3 o'clock, each with a
 * glyph (a page, a meter) on its ring just below its lower end; no numbers: the header reads them
 * out. Both fill upwards. With [fillIn] the arcs composed now fill in from zero, the right one a
 * little after the left; [onFillStarted] follows the first.
 */
@Composable
private fun EdgeGauges(
    shown: Boolean,
    context: ContextUsage?,
    limit: UsageWindow?,
    fillIn: Boolean,
    onFillStarted: () -> Unit,
) {
    AnimatedVisibility(shown, enter = CalmFadeIn, exit = CalmFadeOut) {
        contextPercent(context)?.let { percent ->
            EdgeGauge(
                percent,
                MaterialTheme.colorScheme.primary,
                right = false,
                glyph = R.drawable.ic_context,
                fillDelayMs = if (fillIn) 0L else null,
                onFillStarted = onFillStarted,
            )
        }
        if (limit != null) {
            val color by animateColorAsState(
                limitColor(limit.usedPercent.roundToInt()),
                MaterialTheme.motionScheme.defaultEffectsSpec(),
            )
            EdgeGauge(
                limit.usedPercent,
                color,
                right = true,
                glyph = R.drawable.ic_gauge,
                fillDelayMs = if (fillIn) GAUGE_STAGGER_MS else null,
                onFillStarted = onFillStarted,
            )
        }
    }
}

@Composable
private fun EdgeGauge(
    percent: Double,
    color: Color,
    right: Boolean,
    glyph: Int,
    fillDelayMs: Long?,
    onFillStarted: () -> Unit,
) {
    // As in PercentRing: the indicator observes only the State its first progress lambda reads.
    val progress by rememberFillIn((percent / 100).toFloat().coerceIn(0f, 1f), fillDelayMs, onFillStarted)
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
        // The ends are round.
        colors = ProgressIndicatorDefaults.colors(indicatorColor = color, trackColor = GaugeTrack),
        strokeWidth = GAUGE_STROKE,
    )
    // Upright, on the arc's ring past its lower end, where it fills from: on the round edge, never
    // over the cards. Pulled in from the ring's middle only as far as keeps the glyph's square
    // inside the round screen.
    Layout(
        content = { Icon(painterResource(glyph), null, Modifier.size(GAUGE_GLYPH), tint = MaterialTheme.colorScheme.onSurface) },
        modifier = Modifier.fillMaxSize().padding(edge).clearAndSetSemantics {},
    ) { measurables, constraints ->
        val icon = measurables.single().measure(Constraints())
        layout(constraints.maxWidth, constraints.maxHeight) {
            val radius = constraints.maxWidth / 2f
            val angle = Math.toRadians((if (right) 180f - GAUGE_GLYPH_ANGLE else GAUGE_GLYPH_ANGLE).toDouble())
            val cos = cos(angle).toFloat()
            val sin = sin(angle).toFloat()
            val half = icon.width / 2f
            val ring = minOf(radius - GAUGE_STROKE.toPx() / 2, radius + edge.toPx() - half * (abs(cos) + abs(sin)))
            icon.place((radius + ring * cos - half).roundToInt(), (radius + ring * sin - icon.height / 2f).roundToInt())
        }
    }
}

/**
 * The session's title over its provider badge and status dot, the list's colors, no words: the
 * gauges show the numbers as arcs. Read out as one, the status in words and the numbers too.
 */
@Composable
private fun DetailHeader(session: Session?, limit: UsageWindow?, gone: Boolean, modifier: Modifier) {
    if (session == null) {
        Box(modifier.fillMaxWidth()) { if (gone) CaptionText(stringResource(R.string.detail_gone)) }
        return
    }
    val title = sessionTitle(session)
    val status = statusDescription(session.status)
    val spoken = listOfNotNull(
        title,
        providerLabel(session.provider),
        status,
        contextPercent(session.context)?.let { stringResource(R.string.detail_context_description, it.roundToInt()) },
        limit?.let { "${windowLabel(it)} ${it.usedPercent.roundToInt()}%" },
    ).joinToString(", ")
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderBadge(session.provider)
            StatusDot(session.status)
        }
    }
}

/**
 * An item's time, after its date when it is not from [today]: on screen (no [locale]) in English
 * whatever the language, as the reset clocks ([clockText]: `16:52`, `4:52 PM`; the date as
 * [MONTH_DAY]: `Sep 29 4:52 PM`); for TalkBack in [locale] ([clockWords], an `M/d` date:
 * `9/29 오후 4:52`). In 24 hours when [is24Hour]. Null when [iso] does not parse.
 */
internal fun itemTime(
    iso: String,
    is24Hour: Boolean,
    locale: Locale? = null,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): String? {
    val millis = isoToMillis(iso) ?: return null
    val time = Instant.ofEpochMilli(millis).atZone(zone)
    val clock = if (locale == null) clockText(time, is24Hour) else clockWords(time, locale, is24Hour)
    if (time.toLocalDate() == today) return clock
    val date = if (locale == null) MONTH_DAY.format(time) else DateTimeFormatter.ofPattern("M/d", locale).format(time)
    return "$date $clock"
}

/**
 * An item's time as a small muted caption, read out in the watch's language; formatted once per
 * item (a change of the 12/24-hour setting shows from the screen's next visit), nothing when it
 * does not parse.
 */
@Composable
private fun ItemTime(ts: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val time = remember(ts, is24Hour) { itemTime(ts, is24Hour) } ?: return
    val spoken = remember(ts, is24Hour, locale) { itemTime(ts, is24Hour, locale) } ?: time
    Text(
        time,
        Modifier.semantics { contentDescription = spoken },
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
    )
}

/**
 * When the usage limit an error item reports ends ([limitEnd]): `◷ 7:40 PM` ([resetClockText], as of
 * the item's composition), read out as `resets today 7:40 PM`; `◷ ~7:40 PM` for an estimate, read
 * out as `resets around today 7:40 PM (estimated)`; a coin and `credits` when credits ran out, read
 * out as `credits exhausted`.
 */
@Composable
private fun ItemLimitEnd(end: LimitEnd) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
    val spoken: String
    val glyph: Int
    val text: String
    when (end) {
        is LimitEnd.At -> {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
            val words = resetClockWords(end.millis, now, zone, LocalConfiguration.current.locales[0], is24Hour, stringResource(R.string.limit_today))
            spoken = stringResource(if (end.estimated) R.string.limit_resets_around else R.string.limit_resets_at, words)
            glyph = R.drawable.ic_clock
            text = resetClockText(end.millis, now, zone, is24Hour)
        }
        LimitEnd.Credits -> {
            spoken = stringResource(R.string.limit_credits)
            glyph = R.drawable.ic_credits
            text = "credits"
        }
    }
    Row(
        Modifier.clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(glyph), null, Modifier.size(PLAN_GLYPH), tint = colors.onSurfaceVariant)
        Row {
            // An estimate: a small, dim `~` before the time.
            if (end is LimitEnd.At && end.estimated) Text("~", style = style, color = colors.onSurfaceVariant, maxLines = 1)
            Text(text, style = style, color = colors.onSurface, maxLines = 1)
        }
    }
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
    limitEnd: LimitEnd?,
    expanded: Boolean,
    onToggle: () -> Unit,
    spec: TransformationSpec,
) {
    val colors = MaterialTheme.colorScheme
    // New items fade in. No placement animation: the rows already follow a neighbour's animated
    // size exactly, and a placement spring would trail it.
    val appear = Modifier.animateItemCalmly(this, placement = false)
    // Cards give a little under the finger, but not expanded: a card far taller than the screen
    // would slide rather than shrink.
    val interaction = remember { MutableInteractionSource() }
    val press = Modifier.pressScale(rememberPressDepth(interaction, enabled = !expanded))
    when (item.kind) {
        ItemKind.USER, ItemKind.ASSISTANT -> {
            // An expanded message can be far taller than the screen, and the edge transformation
            // renders its item through an offscreen layer of the full size; skip it there. (It is
            // back for the few frames a long message takes to shrink after collapsing.)
            Card(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth().then(if (expanded) Modifier else Modifier.transformedHeight(this, spec)).then(appear).then(press),
                transformation = if (expanded) null else SurfaceTransformation(spec),
                interactionSource = interaction,
                // Tighter than a Card's 12dp: more of the message on the narrow screen.
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                // A card's content is gray by default; a message is the screen's main text.
                colors = if (item.kind == ItemKind.USER) {
                    CardDefaults.cardColors(containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer)
                } else {
                    CardDefaults.cardColors(contentColor = colors.onSurface)
                },
            ) {
                if (item.plan) {
                    // A plan the agent proposed: the page glyph before its time; TalkBack reads "Plan".
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.ic_context),
                            stringResource(R.string.request_plan_title),
                            Modifier.size(PLAN_GLYPH),
                            tint = colors.primary,
                        )
                        ItemTime(item.ts)
                    }
                } else if (item.error) {
                    // An error the agent wrote into the conversation (e.g. a usage limit): a warning
                    // glyph and a red time, then when the limit ends, when known; TalkBack reads "Error".
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.ic_warning),
                            stringResource(R.string.item_error),
                            Modifier.size(PLAN_GLYPH),
                            tint = colors.error,
                        )
                        ItemTime(item.ts, colors.error)
                        limitEnd?.let { ItemLimitEnd(it) }
                    }
                } else {
                    ItemTime(item.ts)
                }
                Text(
                    if (expanded) item.text else collapsedText(item.text),
                    modifier = Modifier.animateContentSize(CalmMotion.defaultSpatialSpec()),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ItemKind.TOOL -> Card(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).then(appear).then(press),
            transformation = SurfaceTransformation(spec),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            interactionSource = interaction,
            colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (item.pending) SmallSpinner()
                Text(
                    item.text,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    // Gray, as its low card is dark: tool calls step back behind the messages.
                    color = if (item.error) colors.error else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val detail = item.detail
            if (!detail.isNullOrEmpty()) {
                AnimatedVisibility(
                    visible = expanded,
                    enter = CalmFadeIn + expandVertically(CalmMotion.defaultSpatialSpec()),
                    exit = shrinkVertically(CalmMotion.defaultSpatialSpec()) + CalmFadeOut,
                ) {
                    Text(
                        detail,
                        modifier = Modifier.padding(top = 4.dp),
                        // Opened to be read: 12sp, the minimum for text that matters (WO-V14).
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (item.error) colors.error else colors.onSurfaceVariant,
                    )
                }
            }
        }
        else -> Column(
            Modifier.fillMaxWidth().edgeTransform(this, spec).then(appear).minListItemHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ItemTime(item.ts)
            CaptionText(item.text)
        }
    }
}
