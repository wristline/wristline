package dev.wristline.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
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
    SessionListContent(
        conn = conn,
        sessions = sessions,
        requests = requests,
        usage = usage,
        now = rememberNow(),
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
    now: Long,
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
    val chips = usageChips(usage)
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
            if (chips.isNotEmpty()) {
                item(key = "usage") {
                    FilledTonalButton(
                        onClick = onUsage,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).animateItem(),
                        transformation = SurfaceTransformation(spec),
                        label = { Text(chips[0], maxLines = 1) },
                        secondaryLabel = if (chips.size > 1) {
                            { Text(chips.drop(1).joinToString("  "), maxLines = 1) }
                        } else {
                            null
                        },
                    )
                }
            }
            if (sessions.isEmpty() && conn !is Conn.Connecting) {
                item(key = "empty") {
                    BodyText(stringResource(R.string.sessions_empty), Modifier.edgeTransform(this, spec))
                }
            }
            items(sessions, key = { it.id }, contentType = { "session" }) { session ->
                SessionCard(
                    session = session,
                    now = now,
                    onClick = { onSession(session.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, spec)
                        .animateItem()
                        .then(if (stale) Modifier.alpha(0.6f) else Modifier),
                    transformation = SurfaceTransformation(spec),
                )
            }
        }
    }
}

@Composable
internal fun sessionTitle(session: Session): String =
    session.title.ifBlank { basename(session.cwd) }.ifBlank { stringResource(R.string.session_untitled) }

/** `5h 42% · 7d 12%` for Claude Code, `Codex 30%` (first window) for other providers. */
@Composable
private fun usageChips(usage: List<Usage>): List<String> = usage.mapNotNull { u ->
    if (u.windows.isEmpty()) return@mapNotNull null
    if (u.provider == ProviderId.CLAUDE_CODE) {
        u.windows.joinToString(" · ") { "${it.id} ${it.usedPercent.roundToInt()}%" }
    } else {
        "${providerLabel(u.provider)} ${u.windows.first().usedPercent.roundToInt()}%"
    }
}

@Composable
private fun SessionCard(
    session: Session,
    now: Long,
    onClick: () -> Unit,
    modifier: Modifier,
    transformation: SurfaceTransformation,
) {
    TitleCard(
        onClick = onClick,
        modifier = modifier,
        transformation = transformation,
        title = { Text(sessionTitle(session), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        time = { Text(relativeTime(session.lastActivity, now), maxLines = 1) },
        subtitle = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(session.status)
                val place = basename(session.cwd)
                Text(
                    if (place.isEmpty()) providerLabel(session.provider) else "${providerLabel(session.provider)} · $place",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    )
}
