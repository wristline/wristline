package dev.wristline.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
    val showAccounts = remember(sessions, usage) { showAccountLabels(sessions, usage) }
    val lines = usageLines(usage, showAccounts)
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
            if (lines.isNotEmpty()) {
                item(key = "usage") {
                    FilledTonalButton(
                        onClick = onUsage,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).animateItem(),
                        transformation = SurfaceTransformation(spec),
                        label = {
                            Column {
                                lines.take(MAX_USAGE_LINES).forEach { (label, windows) ->
                                    // The numbers always show whole; a long label gives way first.
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (label != null) {
                                            Text(label, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Text(windows, maxLines = 1)
                                    }
                                }
                                if (lines.size > MAX_USAGE_LINES) Text("+${lines.size - MAX_USAGE_LINES}", maxLines = 1)
                            }
                        },
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
                    showAccount = showAccounts,
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

/** Usage entries shown on the list's usage card; the rest are counted as `+N`. */
private const val MAX_USAGE_LINES = 3

/**
 * One (label, windows) line per usage entry, ordered by provider then account label. Claude Code
 * windows keep their short ids and have no label of their own: `5h 42% · 7d 12%`. Other providers'
 * ids are long (`primary`), so only their percentages follow the provider name: `Codex 30%·8%`.
 * With [showAccounts] the short account label joins the label: `~school`, `Codex school`.
 */
@Composable
private fun usageLines(usage: List<Usage>, showAccounts: Boolean): List<Pair<String?, String>> =
    usage.sortedWith(compareBy({ it.provider }, { it.account?.label })).mapNotNull { u ->
        if (u.windows.isEmpty()) return@mapNotNull null
        val account = u.account?.takeIf { showAccounts }?.let(::accountShort)
        if (u.provider == ProviderId.CLAUDE_CODE) {
            account to u.windows.joinToString(" · ") { "${it.id} ${it.usedPercent.roundToInt()}%" }
        } else {
            listOfNotNull(providerLabel(u.provider), account).joinToString(" ") to
                u.windows.joinToString("·") { "${it.usedPercent.roundToInt()}%" }
        }
    }

@Composable
private fun SessionCard(
    session: Session,
    showAccount: Boolean,
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
        // The dot sits on the card's top row: near the screen's bottom edge the list morphs the
        // card's lower corners, which hid a dot at the start of the subtitle.
        time = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(session.status)
                Text(relativeTime(session.lastActivity, now), maxLines = 1)
            }
        },
        subtitle = {
            // `Claude · school · repo`; the account and place parts are dropped when absent.
            val account = session.account?.takeIf { showAccount }?.let(::accountShort)
            val place = basename(session.cwd).ifEmpty { null }
            Text(
                listOfNotNull(providerLabel(session.provider), account, place).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}
