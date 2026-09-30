package dev.wristline.watch.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
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
import dev.wristline.watch.data.PendingRequest
import dev.wristline.watch.data.ProviderId
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.Usage
import kotlin.math.roundToInt

/** The icon buttons at the top of the list. */
private val ACTION_SIZE = 40.dp
private val ACTION_GAP = 8.dp

/** The provider badge beside labelSmall text (the limit card, a session card's second line). */
private val SMALL_BADGE = 14.dp

@Composable
internal fun SessionListScreen(
    onSession: (String) -> Unit,
    onRequest: (String) -> Unit,
    onUsage: () -> Unit,
    onAsk: (String) -> Unit,
    onAskHistory: () -> Unit,
    onSettings: () -> Unit,
    onRepair: () -> Unit,
) {
    val conn by Bridge.conn.collectAsStateWithLifecycle()
    val sessions by Bridge.sessions.collectAsStateWithLifecycle()
    val requests by Bridge.requests.collectAsStateWithLifecycle()
    val usage by Bridge.usage.collectAsStateWithLifecycle()
    val ask = rememberQuickAsk(onStarted = onAsk)
    // Read by the cards' time only, so the minute tick recomposes just the visible cards' time.
    val now = rememberNowState()
    SessionListContent(
        conn = conn,
        sessions = sessions,
        requests = requests,
        usage = usage,
        now = { now.value },
        onSession = onSession,
        onRequest = onRequest,
        onUsage = onUsage,
        onAsk = ask,
        onAskHistory = onAskHistory,
        onSettings = onSettings,
        onRetry = Bridge::retryNow,
        onRepair = onRepair,
    )
}

@Composable
internal fun SessionListContent(
    conn: Conn,
    sessions: List<Session>,
    requests: List<PendingRequest>,
    usage: List<Usage>,
    now: () -> Long,
    onSession: (String) -> Unit,
    onRequest: (String) -> Unit,
    onUsage: () -> Unit,
    onAsk: () -> Unit,
    onAskHistory: () -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    onRepair: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    // Stale data stays readable but visibly out of date.
    val stale = conn is Conn.Offline || conn is Conn.Unreachable || conn is Conn.Unauthorized
    val oldestRequest = remember(requests) { requests.minByOrNull { it.createdAt } }
    val glance = remember(usage) { glanceUsage(usage) }
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            flingBehavior = TransformingLazyColumnDefaults.snapFlingBehavior(listState),
            rotaryScrollableBehavior = RotaryScrollableDefaults.snapBehavior(listState),
        ) {
            // In place of a title: Settings, Quick Ask (the primary action) and the recent questions.
            item(key = "actions") {
                Row(
                    Modifier.fillMaxWidth().edgeTransform(this, spec),
                    horizontalArrangement = Arrangement.spacedBy(ACTION_GAP, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val iconSize = IconButtonDefaults.iconSizeFor(ACTION_SIZE)
                    // Round, squarer while pressed.
                    val shapes = IconButtonDefaults.animatedShapes()
                    FilledTonalIconButton(onClick = onSettings, modifier = Modifier.size(ACTION_SIZE), shapes = shapes) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings_title), Modifier.size(iconSize))
                    }
                    FilledIconButton(onClick = onAsk, modifier = Modifier.size(ACTION_SIZE), shapes = shapes) {
                        Icon(painterResource(R.drawable.ic_mic), stringResource(R.string.ask_button), Modifier.size(iconSize))
                    }
                    FilledTonalIconButton(onClick = onAskHistory, modifier = Modifier.size(ACTION_SIZE), shapes = shapes) {
                        Icon(painterResource(R.drawable.ic_history), stringResource(R.string.ask_history_title), Modifier.size(iconSize))
                    }
                }
            }
            if (oldestRequest != null) {
                item(key = "requests") {
                    val colors = MaterialTheme.colorScheme
                    Button(
                        onClick = { onRequest(oldestRequest.id) },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).animateItemCalmly(this),
                        transformation = SurfaceTransformation(spec),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.tertiary, contentColor = colors.onTertiary),
                        label = {
                            Text(pluralStringResource(R.plurals.requests_waiting, requests.size, requests.size))
                        },
                        secondaryLabel = {
                            val session = sessions.firstOrNull { it.id == oldestRequest.sessionId }
                            Text(session?.let { sessionTitle(it) } ?: oldestRequest.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                    )
                }
            }
            if (conn.hasBanner()) {
                item(key = "conn", contentType = "conn") { ConnBanner(conn, spec, onRetry, onRepair) }
            }
            if (glance.isNotEmpty()) {
                item(key = "usage") {
                    val interaction = remember { MutableInteractionSource() }
                    val depth = rememberPressDepth(interaction)
                    Card(
                        onClick = onUsage,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).animateItemCalmly(this).pressScale(depth),
                        transformation = SurfaceTransformation(spec),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        interactionSource = interaction,
                    ) {
                        // One line for both providers; the second wraps under the first only when
                        // they do not fit side by side.
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            glance.forEach { GlanceEntry(it) }
                        }
                    }
                }
            }
            // Only an up-to-date list can say there are none; otherwise the banner explains.
            if (sessions.isEmpty() && (conn is Conn.Online || conn is Conn.Demo)) {
                item(key = "empty") {
                    BodyText(stringResource(R.string.sessions_empty), Modifier.edgeTransform(this, spec))
                }
            }
            items(sessions, key = { it.id }, contentType = { "session" }) { session ->
                SessionCard(
                    session = session,
                    stale = stale,
                    now = now,
                    spec = spec,
                    onClick = { onSession(session.id) },
                )
            }
        }
    }
}

@Composable
internal fun sessionTitle(session: Session): String =
    session.title.ifBlank { basename(session.cwd) }.ifBlank { stringResource(R.string.session_untitled) }

/** A limit window's percentage from here up is shown in yellow (see [limitColor]). */
internal const val NEAR_LIMIT_PERCENT = 80

/** A limit window's percentage from here up is shown in red (see [limitColor]). */
internal const val AT_LIMIT_PERCENT = 95

/**
 * The usage card's entries: one per provider, in provider order. Of a provider's accounts only the
 * one closest to a limit (highest used percentage in any window) is shown; the Usage screen has
 * the rest. Entries without windows are skipped.
 */
internal fun glanceUsage(usage: List<Usage>): List<Usage> =
    usage.filter { it.windows.isNotEmpty() }
        .groupBy { it.provider }
        .toSortedMap()
        .values
        .map { entries -> entries.maxBy { u -> u.windows.maxOf { it.usedPercent } } }

/**
 * `[C] 5h 14% · 7d 40%`, `[C] 12% · 40%`: a provider's badge and its windows. Claude Code windows
 * keep their short ids, other providers' ids are long (`primary`) so only the percentages show,
 * except for a lone window, named by its label or length (`[C] 7d 2%`). The names are muted; the
 * numbers take [limitColor].
 */
@Composable
private fun GlanceEntry(u: Usage) {
    val colors = MaterialTheme.colorScheme
    val muted = SpanStyle(color = colors.onSurfaceVariant)
    val windows = buildAnnotatedString {
        u.windows.forEachIndexed { i, w ->
            if (i > 0) withStyle(muted) { append(" · ") }
            val name = when {
                u.provider == ProviderId.CLAUDE_CODE -> w.id
                u.windows.size == 1 -> w.label ?: windowShort(w)
                else -> null
            }
            if (name != null) withStyle(muted) { append("$name ") }
            val percent = w.usedPercent.roundToInt()
            withStyle(SpanStyle(color = limitColor(percent))) { append("$percent%") }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        ProviderBadge(u.provider, size = SMALL_BADGE)
        Text(windows, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// Builds its modifier and transformation itself, as ItemRow does: made by the caller they would be
// new on every recomposition of the list, and the card could never skip.
@Composable
private fun TransformingLazyColumnItemScope.SessionCard(
    session: Session,
    stale: Boolean,
    now: () -> Long,
    spec: TransformationSpec,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val depth = rememberPressDepth(interaction)
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, spec)
            .animateItemCalmly(this)
            .pressScale(depth)
            .then(if (stale) Modifier.alpha(0.6f) else Modifier),
        transformation = SurfaceTransformation(spec),
        // A card's content is gray by default; the name is white, the line under it gray.
        colors = CardDefaults.cardColors(contentColor = MaterialTheme.colorScheme.onSurface),
        interactionSource = interaction,
    ) {
        // Line 1: the status dot and the session's name, which tells cards apart: the largest text.
        // The dot sits on the top line: near the screen's bottom edge the list morphs the card's
        // lower corners, which would hide it at the start of the second line.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(session.status)
            Text(sessionTitle(session), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Line 2, muted: `12 min. ago · [C] repo`; accounts are on the Usage screen.
        // Recomputed once per tick and activity, not on every recomposition of the card.
        val at = now()
        val ago = remember(session.lastActivity, at) {
            if (isJustNow(session.lastActivity, at)) null else relativeTime(session.lastActivity, at)
        }
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Row(
            Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text((ago ?: stringResource(R.string.time_just_now)) + " ·", color = muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            ProviderBadge(session.provider, size = SMALL_BADGE)
            Text(basename(session.cwd), color = muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
