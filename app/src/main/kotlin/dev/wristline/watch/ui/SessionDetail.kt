package dev.wristline.watch.ui

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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.AnchorType
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.LocalReduceMotion
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
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
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
import dev.wristline.watch.data.Haptics
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
import dev.wristline.watch.data.sentHaptic
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
// How long the speak button shows a check after a prompt went out.
private const val SENT_CHECK_MS = 1_200L
private val ACTION_SIZE = 44.dp
private val ACTION_GAP = 10.dp
// Low in the round screen's bottom chin, the two buttons still inside the circle.
private val ACTIONS_BOTTOM = 6.dp
// The list's end padding as a share of the screen height: at rest the newest short card ends about
// 70% down the screen, a little above the actions.
private const val END_PADDING_FRACTION = 0.3f
// Behind the actions, rising well above them: content scrolling under them fades out.
private val SCRIM_HEIGHT = 72.dp
// The list's top padding: under the small top text (2dp from the edge, about 13dp at 11sp) with
// a few dp to spare.
private val LIST_TOP = 20.dp
// The list's side padding comes this much inside the default. No more: the round edge clips
// wider cards.
private val LIST_SIDE_TRIM = 2.dp

// The curved top text (model and effort, in the time text's place) at its widest, centered on 12
// o'clock (270 degrees clockwise from 3 o'clock); its background's round ends add about 5 degrees
// on each side, so it spans at most about 234 to 306 degrees.
private const val TIME_TEXT_SWEEP = 62f
// Left gauge centered on 9 o'clock, in degrees clockwise from 3 o'clock; the right gauge mirrors it
// (338 to 22, centered on 3 o'clock, where the scroll indicator is: it is hidden while the gauges
// show). Past each upper end, GAUGE_LABEL_GAP on, its percentage, the only place it shows: `100%`
// at GAUGE_LABEL_SIZE takes about 19 degrees, so it ends near 224 (316 on the right), short of the
// widest time text's background, which starts at 234; at a 1.3 font scale, near 230.
private const val GAUGE_START = 158f
private const val GAUGE_SWEEP = 44f
// Clears the arc's round end (half the stroke, under 2 degrees) with a little room to spare.
private const val GAUGE_LABEL_GAP = 3f
private val GAUGE_LABEL_SIZE = 12.sp
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
                        val sent = Bridge.prompt(sessionId, draft)
                        outcome = sent
                        sending = false
                        Haptics.touch(context, sentHaptic(sent))
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
        timeText = { DetailTopText(session) },
        // The scaffold keeps the indicator (at 3 o'clock, under the right gauge) for 2s after a
        // scroll, the gauges come back after GAUGE_SETTLE_MS: hidden while they show.
        scrollIndicator = {
            AnimatedVisibility(!gaugesShown.arcs, enter = CalmFadeIn, exit = CalmFadeOut) { ScrollIndicator(listState) }
        },
    ) { contentPadding ->
        val layoutDirection = LocalLayoutDirection.current
        // Default rotary behaviour (fling with haptics): long messages are read continuously, not item by item.
        TransformingLazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(layoutDirection) - LIST_SIDE_TRIM,
                top = LIST_TOP,
                end = contentPadding.calculateEndPadding(layoutDirection) - LIST_SIDE_TRIM,
                bottom = endPadding,
            ),
        ) {
            item(key = HEADER_KEY) {
                DetailHeader(
                    session = session,
                    limit = limit,
                    gone = gone,
                    modifier = Modifier.edgeTransform(this, spec),
                )
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
                    CaptionText(stringResource(R.string.detail_working), Modifier.edgeTransform(this, spec).animateItemCalmly(this))
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
                        Modifier.edgeTransform(this, spec).animateItemCalmly(this),
                        color = if (outcome == Sent.Ok) colors.primary else colors.error,
                    )
                }
            }
        }
        // The content is a Box: what is composed after the list is drawn over it. The arcs show
        // whenever the list is at rest, their labels only at the top, with the whole header.
        if (session != null) {
            EdgeGauges(gaugesShown, session.context, limit, fillIn = !gaugesFilled, onFillStarted = { gaugesFilled = true })
        }
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
                // Round, squarer while pressed, but only so far: square corners this low would reach
                // past the round screen's edge. The same size, so the pair never shifts.
                val shapes = IconButtonDefaults.animatedShapes(pressedShape = MaterialTheme.shapes.medium)
                FilledIconButton(onClick = onAction, enabled = canSend, modifier = Modifier.size(ACTION_SIZE), shapes = shapes) {
                    SpeakIcon(
                        when {
                            sending -> SpeakState.SENDING
                            sentCheck -> SpeakState.SENT
                            else -> SpeakState.MIC
                        },
                    )
                }
                FilledTonalIconButton(onClick = onType, enabled = canSend, modifier = Modifier.size(ACTION_SIZE), shapes = shapes) {
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

/** Which parts of the edge gauges show: see [rememberGaugesShown]. */
private data class GaugesShown(val arcs: Boolean, val labels: Boolean)

/**
 * Which parts of the edge gauges show. The arcs, only while the list is at rest (they fade out as
 * soon as it scrolls and back in [GAUGE_SETTLE_MS] after it stops), wherever it is scrolled to;
 * the labels, also only at the top of the transcript, while the header (the first item) is fully
 * on screen.
 */
@Composable
private fun rememberGaugesShown(listState: TransformingLazyColumnState): State<GaugesShown> {
    // Not scrolled for the last GAUGE_SETTLE_MS.
    var resting by remember(listState) { mutableStateOf(!listState.isScrollInProgress) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling ->
            if (!scrolling) delay(GAUGE_SETTLE_MS)
            resting = !scrolling
        }
    }
    return remember(listState) {
        derivedStateOf {
            // An item's offset is its top from the top of the list's viewport (the screen), not from
            // the content padding: the header, at LIST_TOP when scrolled to the top, is fully in view
            // until its top goes past the screen's.
            val first = listState.layoutInfo.visibleItems.firstOrNull()
            val atTop = first?.let { it.index == 0 && it.offset >= 0 } == true
            GaugesShown(arcs = resting, labels = resting && atTop)
        }
    }
}

/**
 * Context use (left) and the limit window (right) as bare arcs centered on 9 and 3 o'clock, each
 * with its percentage just past its upper end; the header only reads the numbers out. Both fill
 * upwards, towards the time text. With [fillIn] the arcs composed now fill in from zero, the right
 * one a little after the left; [onFillStarted] follows the first.
 */
@Composable
private fun EdgeGauges(
    shown: GaugesShown,
    context: ContextUsage?,
    limit: UsageWindow?,
    fillIn: Boolean,
    onFillStarted: () -> Unit,
) {
    AnimatedVisibility(shown.arcs, enter = CalmFadeIn, exit = CalmFadeOut) {
        contextPercent(context)?.let { percent ->
            EdgeGauge(
                percent,
                MaterialTheme.colorScheme.primary,
                right = false,
                labelShown = shown.labels,
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
                labelShown = shown.labels,
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
    labelShown: Boolean,
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
    // Not mirrored, so it reads left to right on both sides: on the left it starts past the upper
    // end, on the right it ends before it.
    val upperEnd = GAUGE_START + GAUGE_SWEEP
    AnimatedVisibility(labelShown, enter = CalmFadeIn, exit = CalmFadeOut) {
        CurvedLayout(
            Modifier.fillMaxSize().padding(edge).clearAndSetSemantics {},
            anchor = if (right) 540f - upperEnd - GAUGE_LABEL_GAP else upperEnd + GAUGE_LABEL_GAP,
            anchorType = if (right) AnchorType.End else AnchorType.Start,
        ) {
            curvedText("${percent.roundToInt()}%", color = color, fontSize = GAUGE_LABEL_SIZE, fontWeight = FontWeight.Medium)
        }
    }
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
    // Small and muted, well under the time text's 15sp: it takes little of the screen's top.
    val style = TimeTextDefaults.timeTextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    TimeText(maxSweepAngle = TIME_TEXT_SWEEP) {
        if (model == null && effort == null) {
            curvedText(provider, overflow = TextOverflow.Ellipsis, style = style)
            return@TimeText
        }
        // Weighted, the model takes what the effort and separator leave.
        if (model != null) curvedText(model, CurvedModifier.weight(1f), overflow = TextOverflow.Ellipsis, style = style)
        if (model != null && effort != null) timeTextSeparator(style)
        if (effort != null) curvedText(effort, style = style)
    }
}

/**
 * The session's title over its provider badge, status dot and status in a word: the model and the
 * gauges' numbers show once, at the top and at the arcs' ends. Read out as one, the numbers too.
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
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
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
                ItemTime(item.ts)
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
