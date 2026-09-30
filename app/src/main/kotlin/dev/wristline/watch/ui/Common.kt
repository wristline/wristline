package dev.wristline.watch.ui

import android.app.Activity
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.input.RemoteInputIntentHelper
import dev.wristline.watch.R
import dev.wristline.watch.data.Conn
import dev.wristline.watch.data.ProviderId
import dev.wristline.watch.data.SessionStatus
import dev.wristline.watch.data.isoToMillis
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The list's scale-and-fade near the round edge, for items without a Surface (plain text, rows).
 * Material components take `transformation = SurfaceTransformation(spec)` instead.
 */
fun Modifier.edgeTransform(scope: TransformingLazyColumnItemScope, spec: TransformationSpec): Modifier =
    transformedHeight(scope, spec).graphicsLayer {
        with(scope) { with(spec) { applyContainerTransformation(scrollProgress) } }
    }

/** Wall-clock time refreshed every [periodMs] while the screen is at least STARTED. */
@Composable
fun rememberNow(periodMs: Long = 60_000): Long = rememberNowState(periodMs).value

/** [rememberNow] as a State: a tick recomposes only the scopes that read it. */
@Composable
fun rememberNowState(periodMs: Long = 60_000): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, periodMs) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now.longValue = System.currentTimeMillis()
                delay(periodMs)
            }
        }
    }
    return now
}

fun relativeTime(iso: String?, now: Long): String {
    val millis = isoToMillis(iso) ?: return ""
    return DateUtils.getRelativeTimeSpanString(
        minOf(millis, now),
        now,
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

/** Under a minute [relativeTime] would read "0 min. ago"; callers show "Just now" instead. */
fun isJustNow(iso: String?, now: Long): Boolean {
    val millis = isoToMillis(iso) ?: return false
    return now - millis < 60_000
}

/** Localized short time, prefixed by the weekday when not today. */
fun clockTime(iso: String?, locale: Locale): String? {
    val millis = isoToMillis(iso) ?: return null
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(millis).atZone(zone)
    val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(time)
    if (time.toLocalDate() == LocalDate.now(zone)) return clock
    return time.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) + " " + clock
}

fun basename(path: String): String = path.trimEnd('/').substringAfterLast('/')

@Composable
fun providerLabel(provider: String): String = when (provider) {
    ProviderId.CLAUDE_CODE -> stringResource(R.string.provider_claude_code)
    ProviderId.CODEX -> stringResource(R.string.provider_codex)
    else -> provider
}

/** Claude's warm orange, darkened from #D97757 to 3.9:1 on the badge's white. */
private val ClaudeOrange = Color(0xFFC96442)

/** Codex blue: white on it is 4.1:1, and it is 3.2:1 on the cards' dark surface. */
private val CodexBlue = Color(0xFF2A7FD4)

/**
 * A provider's 16dp monogram: an orange C on white for Claude Code, a white C on blue for Codex,
 * otherwise the id's first letter in an outlined circle. Read out as the provider's name.
 */
@Composable
fun ProviderBadge(provider: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val label = providerLabel(provider)
    val (fill, letterColor) = when (provider) {
        ProviderId.CLAUDE_CODE -> Modifier.background(Color.White, CircleShape) to ClaudeOrange
        ProviderId.CODEX -> Modifier.background(CodexBlue, RoundedCornerShape(4.dp)) to Color.White
        else -> Modifier.border(1.dp, colors.outline, CircleShape) to colors.onSurfaceVariant
    }
    // A fixed-size mark, so the letter is sized in dp: in sp a large font scale would overflow it.
    val letterStyle = with(LocalDensity.current) {
        MaterialTheme.typography.labelSmall.copy(fontSize = 12.dp.toSp(), lineHeight = 16.dp.toSp())
    }
    Box(
        modifier.size(16.dp).then(fill).clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val letter = provider.take(1).uppercase()
        Text(
            letter,
            // Centered by its advance a C sits 0.7dp right, its left side bearing being the wider;
            // pulled back by half a dp its open side no longer looks lopsided.
            modifier = if (letter == "C") Modifier.offset(x = (-0.5).dp) else Modifier,
            color = letterColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            style = letterStyle,
        )
    }
}

@Composable
fun StatusDot(status: String, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    val description = statusDescription(status)
    Box(
        modifier
            .size(8.dp)
            .background(color, CircleShape)
            .semantics { contentDescription = description },
    )
}

@Composable
fun statusColor(status: String): Color {
    val colors = MaterialTheme.colorScheme
    return when (status) {
        SessionStatus.NEEDS_INPUT -> colors.error
        SessionStatus.RUNNING -> colors.primary
        SessionStatus.IDLE -> colors.onSurfaceVariant
        else -> colors.outline
    }
}

@Composable
fun statusDescription(status: String): String = when (status) {
    SessionStatus.NEEDS_INPUT -> stringResource(R.string.status_needs_input)
    SessionStatus.RUNNING -> stringResource(R.string.status_running)
    SessionStatus.IDLE -> stringResource(R.string.status_idle)
    else -> stringResource(R.string.status_ended)
}

/** Circular arc for a percentage; the indicator animates later changes itself. */
@Composable
fun PercentRing(percent: Double, modifier: Modifier = Modifier, strokeWidth: Dp = 4.dp) {
    val fraction = (percent / 100).toFloat().coerceIn(0f, 1f)
    // The indicator keeps the first progress lambda and observes only the State it reads: a lambda
    // over a plain Float would never report a later percent.
    val progress by rememberUpdatedState(fraction)
    val colors = if (percent >= 90) {
        ProgressIndicatorDefaults.colors(indicatorColor = MaterialTheme.colorScheme.error)
    } else {
        ProgressIndicatorDefaults.colors()
    }
    CircularProgressIndicator(progress = { progress }, modifier = modifier, colors = colors, strokeWidth = strokeWidth)
}

/** Small indeterminate spinner: used only for pending tool calls and connecting/sending states. */
@Composable
fun SmallSpinner(modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier = modifier.size(16.dp), strokeWidth = 2.dp)
}

@Composable
fun errorMessage(code: String): String = when (code) {
    "unreachable" -> stringResource(R.string.error_unreachable)
    "invalid_code" -> stringResource(R.string.error_invalid_code)
    "rate_limited" -> stringResource(R.string.error_rate_limited)
    "incompatible" -> stringResource(R.string.error_incompatible)
    "unauthorized" -> stringResource(R.string.error_unauthorized)
    "already_resolved" -> stringResource(R.string.error_already_resolved)
    "payload_too_large" -> stringResource(R.string.error_too_long)
    "bad_request" -> stringResource(R.string.error_bad_request)
    // A prompt to a session the bridge no longer has (pairing maps it to its own message).
    "not_found" -> stringResource(R.string.detail_gone)
    "not_live" -> stringResource(R.string.block_not_live)
    "no_tmux" -> stringResource(R.string.block_no_tmux)
    "awaiting_input" -> stringResource(R.string.block_awaiting_input)
    "busy" -> stringResource(R.string.block_busy)
    "unsupported" -> stringResource(R.string.block_unsupported)
    "unsafe_prefix" -> stringResource(R.string.block_unsafe_prefix)
    else -> stringResource(R.string.error_generic, code)
}

private const val INPUT_KEY = "text"

/**
 * Returns a launcher for the system text input (keyboard, dictation or phone). [onText] gets the
 * trimmed, non-empty result. A watch without a text input activity gets [inputUnavailable].
 */
@Composable
fun rememberTextInput(label: String, onText: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val tryType = rememberTextInputLauncher(label, onText)
    return remember(tryType) { { if (!tryType()) inputUnavailable(context) } }
}

/**
 * [rememberTextInput] without the message: returns false when the input activity could not be
 * started, so the caller can try another way in first.
 */
@Composable
fun rememberTextInputLauncher(label: String, onText: (String) -> Unit): () -> Boolean {
    val latest by rememberUpdatedState(onText)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) return@rememberLauncherForActivityResult
        val text = RemoteInput.getResultsFromIntent(data)?.getCharSequence(INPUT_KEY)?.toString()?.trim()
        if (!text.isNullOrEmpty()) latest(text)
    }
    return remember(launcher, label) {
        {
            val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
            RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder(INPUT_KEY).setLabel(label).build()))
            try {
                launcher.launch(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                // The activity exists but this watch does not let third-party apps start it.
                false
            }
        }
    }
}

/** Says that nothing on this watch can take the input (no text input activity or speech recognizer). */
fun inputUnavailable(context: Context) {
    Toast.makeText(context, R.string.input_unavailable, Toast.LENGTH_SHORT).show()
}

fun Conn.hasBanner(): Boolean = when (this) {
    Conn.Online, Conn.NotPaired -> false
    else -> true
}

/**
 * Connection state as a list item: says what is wrong and offers the one useful action. Applies
 * the list's edge transformation and item appearance animation itself.
 */
@Composable
fun TransformingLazyColumnItemScope.ConnBanner(
    conn: Conn,
    spec: TransformationSpec,
    onRetry: () -> Unit,
    onRepair: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val surface = Modifier.fillMaxWidth().transformedHeight(this, spec).animateItem()
    val plain = Modifier.fillMaxWidth().edgeTransform(this, spec).animateItem()
    when (conn) {
        is Conn.Unreachable -> {
            val now = rememberNow(periodMs = 1_000)
            val seconds = ((conn.retryAt - now + 999) / 1000).coerceAtLeast(0).toInt()
            FilledTonalButton(
                onClick = onRetry,
                modifier = surface,
                transformation = SurfaceTransformation(spec),
                label = { Text(stringResource(R.string.conn_unreachable)) },
                secondaryLabel = { Text(stringResource(R.string.conn_retry_in, seconds)) },
            )
        }
        Conn.Unauthorized -> Button(
            onClick = onRepair,
            modifier = surface,
            transformation = SurfaceTransformation(spec),
            colors = ButtonDefaults.buttonColors(containerColor = colors.errorContainer, contentColor = colors.onErrorContainer),
            label = { Text(stringResource(R.string.conn_unauthorized)) },
            secondaryLabel = { Text(stringResource(R.string.conn_repair)) },
        )
        is Conn.Incompatible -> FilledTonalButton(
            onClick = onRetry,
            modifier = surface,
            transformation = SurfaceTransformation(spec),
            label = { Text(stringResource(R.string.conn_incompatible)) },
            secondaryLabel = { Text(stringResource(R.string.conn_incompatible_detail, conn.apiVersion)) },
        )
        Conn.Connecting -> Row(
            plain,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallSpinner()
            Text(stringResource(R.string.conn_connecting), style = MaterialTheme.typography.labelSmall)
        }
        Conn.Offline -> CaptionText(stringResource(R.string.conn_offline), plain, color = colors.error)
        Conn.Demo -> CaptionText(stringResource(R.string.conn_demo), plain, color = colors.tertiary)
        Conn.Online, Conn.NotPaired -> Unit
    }
}

@Composable
fun CaptionText(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        modifier = modifier.fillMaxWidth(),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
    )
}
