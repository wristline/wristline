package dev.wristline.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
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
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
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

@Composable
internal fun SessionListScreen(
    onSession: (String) -> Unit,
    onRequest: (String) -> Unit,
    onUsage: () -> Unit,
    onSettings: () -> Unit,
    onRepair: () -> Unit,
) {
    val conn by Bridge.conn.collectAsStateWithLifecycle()
    val sessions by Bridge.sessions.collectAsStateWithLifecycle()
    val requests by Bridge.requests.collectAsStateWithLifecycle()
    val usage by Bridge.usage.collectAsStateWithLifecycle()
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
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(onClick = onSettings, buttonSize = EdgeButtonSize.Small) { Text(stringResource(R.string.settings_title)) }
        },
    ) { contentPadding ->
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
                ) { Text(stringResource(R.string.sessions_title)) }
            }
            if (conn.hasBanner()) {
                item(key = "conn", contentType = "conn") { ConnBanner(conn, spec, onRetry, onRepair) }
            }
            if (oldestRequest != null) {
                item(key = "requests") {
                    val colors = MaterialTheme.colorScheme
                    Button(
                        onClick = { onRequest(oldestRequest.id) },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).animateItem(),
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
            if (glance.isNotEmpty()) {
                item(key = "usage") {
                    FilledTonalButton(
                        onClick = onUsage,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).animateItem(),
                        transformation = SurfaceTransformation(spec),
                        // The cards' start padding, so the badges line up with the session cards' below.
                        contentPadding = PaddingValues(
                            start = CardDefaults.ContentPadding.calculateStartPadding(LocalLayoutDirection.current),
                            top = ButtonDefaults.ButtonVerticalPadding,
                            end = ButtonDefaults.ButtonHorizontalPadding,
                            bottom = ButtonDefaults.ButtonVerticalPadding,
                        ),
                        label = { Column { glance.forEach { GlanceLine(it) } } },
                    )
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

/** Percentages from here up are shown in the error color. */
internal const val NEAR_LIMIT_PERCENT = 80

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
 * `[C]     5h 14% · 7d 40%`, `[C]     12% · 40%`, each after its provider's badge: Claude Code
 * windows keep their short ids, other providers' ids are long (`primary`) so only the percentages
 * show, except for a lone window, named by its label or length (`[C]     7d 2%`). The numbers are
 * larger and tabular, the names smaller and muted; a number near its limit takes the error color.
 */
@Composable
private fun GlanceLine(u: Usage) {
    val colors = MaterialTheme.colorScheme
    val number = SpanStyle(fontSize = MaterialTheme.typography.titleMedium.fontSize, fontFeatureSettings = "tnum")
    val muted = SpanStyle(fontSize = MaterialTheme.typography.bodySmall.fontSize, color = colors.onSurfaceVariant)
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
            withStyle(if (percent >= NEAR_LIMIT_PERCENT) number.copy(color = colors.error) else number) { append("$percent%") }
        }
    }
    // The badge is measured first and always shows whole; the numbers take the rest.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ProviderBadge(u.provider)
        // End-aligned: a button centers its label text.
        Text(windows, Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 1)
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
    TitleCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, spec)
            .animateItem()
            .then(if (stale) Modifier.alpha(0.6f) else Modifier),
        transformation = SurfaceTransformation(spec),
        // The folder is secondary to the title: muted, not the accent.
        colors = CardDefaults.cardColors(subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant),
        // The session's name is what tells cards apart: the largest text, up to two lines.
        title = {
            Text(sessionTitle(session), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        // The dot sits on the card's top row: near the screen's bottom edge the list morphs the
        // card's lower corners, which hid a dot at the start of the subtitle.
        time = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(session.status)
                // Recomputed once per tick and activity, not on every recomposition of the card.
                val at = now()
                val ago = remember(session.lastActivity, at) {
                    if (isJustNow(session.lastActivity, at)) null else relativeTime(session.lastActivity, at)
                }
                Text(ago ?: stringResource(R.string.time_just_now), maxLines = 1)
            }
        },
        subtitle = {
            // `[C] repo`: the provider's badge, then the folder; accounts are on the Usage screen.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ProviderBadge(session.provider)
                Text(basename(session.cwd), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
    )
}
