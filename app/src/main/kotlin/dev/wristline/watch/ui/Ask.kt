package dev.wristline.watch.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import dev.wristline.watch.R
import dev.wristline.watch.data.Ask
import dev.wristline.watch.data.AskSent
import dev.wristline.watch.data.AskStatus
import dev.wristline.watch.data.Bridge
import dev.wristline.watch.data.Conn
import dev.wristline.watch.data.ProviderId
import java.util.Locale
import kotlinx.coroutines.launch

/** Seconds with one decimal, as the answer header shows the time the CLI took: 1389 ms is `1.4`. */
internal fun askSeconds(durationMs: Long): String = String.format(Locale.ROOT, "%.1f", durationMs / 1000.0)

/** The provider a question goes to when the user asks with the other one. */
internal fun otherProvider(provider: String): String =
    if (provider == ProviderId.CODEX) ProviderId.CLAUDE_CODE else ProviderId.CODEX

/**
 * The session list's Ask button: speech (or typing) in, a confirm dialog with the provider badge
 * to toggle Claude and Codex, then `POST /api/ask`. [onStarted] gets the new askId. The returned
 * lambda starts the flow; the dialog and any refusal toast are composed here.
 */
@Composable
internal fun rememberQuickAsk(onStarted: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val prefs = Bridge.prefs
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf(prefs.askProvider) }
    var error by remember { mutableStateOf<String?>(null) }
    val label = stringResource(R.string.ask_prompt_label)
    val tryType = rememberTextInputLauncher(label) {
        draft = it
        typed = true
        confirming = true
    }
    val trySpeak = rememberSpeechInput(label) {
        draft = it
        typed = false
        confirming = true
    }
    val type = remember(tryType, trySpeak) { { if (!tryType() && !trySpeak()) inputUnavailable(context) } }
    val speak = remember(tryType, trySpeak) { { if (!trySpeak() && !tryType()) inputUnavailable(context) } }
    val latestStarted by rememberUpdatedState(onStarted)

    val code = error
    if (code != null) {
        val message = if (code == "unreachable") stringResource(R.string.error_unreachable) else errorMessage(code)
        LaunchedEffect(code) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            error = null
        }
    }

    AlertDialog(
        visible = confirming,
        onDismissRequest = { confirming = false },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    confirming = false
                    scope.launch {
                        when (val sent = Bridge.ask(provider, draft)) {
                            is AskSent.Started -> latestStarted(sent.askId)
                            is AskSent.Refused -> error = sent.code
                            AskSent.Unreachable -> error = "unreachable"
                        }
                    }
                },
            )
        },
        title = { Text(stringResource(R.string.ask_confirm_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Tapping the badge switches the provider and makes it the default for next time.
                ProviderChip(
                    provider = provider,
                    onClick = {
                        provider = otherProvider(provider)
                        prefs.askProvider = provider
                    },
                )
                Text(draft, textAlign = TextAlign.Center)
                CaptionText(stringResource(R.string.ask_uses_plan))
            }
        },
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

    return remember(speak) {
        {
            when (Bridge.conn.value) {
                // Same message as the list's banner: nothing can be sent until the watch pairs again.
                Conn.Unauthorized, Conn.NotPaired -> Toast.makeText(context, R.string.conn_unauthorized, Toast.LENGTH_SHORT).show()
                else -> speak()
            }
        }
    }
}

/** The provider's badge with its name beside it (never colour alone), as one 48dp-tall tap target. */
@Composable
private fun ProviderChip(provider: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick, role = Role.Button)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderBadge(provider)
        Text(providerLabel(provider), style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * One question and its answer. [Ask again] and [Ask Claude/Codex] send a new question and
 * [onReplaced] swaps this screen for the new ask's. Leaving while the question is still running
 * cancels it.
 */
@Composable
internal fun AskScreen(askId: String, onReplaced: (String) -> Unit) {
    val asks by Bridge.asks.collectAsStateWithLifecycle()
    val conn by Bridge.conn.collectAsStateWithLifecycle()
    val ask = remember(asks, askId) { asks.firstOrNull { it.id == askId } }
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    val running by rememberUpdatedState(ask?.status == AskStatus.RUNNING)
    DisposableEffect(askId) {
        onDispose { if (running) Bridge.cancelAsk(askId) }
    }

    val code = error
    if (code != null) {
        val message = if (code == "unreachable") stringResource(R.string.error_unreachable) else errorMessage(code)
        LaunchedEffect(code) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            error = null
        }
    }

    val resend = { provider: String ->
        val current = ask
        if (current != null && !sending) {
            sending = true
            scope.launch {
                // One running ask per device: the bridge would answer 409 while this one runs.
                if (current.status == AskStatus.RUNNING) Bridge.cancelAsk(current.id).join()
                when (val sent = Bridge.ask(provider, current.question)) {
                    is AskSent.Started -> onReplaced(sent.askId)
                    is AskSent.Refused -> error = sent.code
                    AskSent.Unreachable -> error = "unreachable"
                }
                sending = false
            }
        }
    }

    if (ask == null && conn !is Conn.Online && conn !is Conn.Demo) {
        // The list is fetched after the socket comes up; until then this may still be answered.
        StatusScreen(stringResource(R.string.conn_connecting), spinner = true)
        return
    }
    AskContent(
        ask = ask,
        sending = sending,
        onCancel = { Bridge.cancelAsk(askId) },
        onAgain = { ask?.let { resend(it.provider) } },
        onOther = { ask?.let { resend(otherProvider(it.provider)) } },
    )
}

/** [ask] null: the bridge no longer has it (restarted). */
@Composable
internal fun AskContent(
    ask: Ask?,
    sending: Boolean,
    onCancel: () -> Unit,
    onAgain: () -> Unit,
    onOther: () -> Unit,
) {
    if (ask == null) {
        StatusScreen(stringResource(R.string.ask_gone), spinner = false)
        return
    }
    if (ask.status == AskStatus.RUNNING) {
        Thinking(ask.question, onCancel)
        return
    }
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val colors = MaterialTheme.colorScheme
    val name = providerLabel(ask.provider)
    val other = if (ask.provider == ProviderId.CODEX) R.string.ask_other_claude else R.string.ask_other_codex
    ScreenScaffold(scrollState = listState) { contentPadding ->
        // Default rotary (fling with haptics): an answer is read continuously, not card by card.
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item(key = "header") {
                ListHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, spec)
                        .minimumVerticalContentPadding(ListHeaderDefaults.minimumTopListContentPadding),
                    transformation = SurfaceTransformation(spec),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProviderBadge(ask.provider)
                        Text(listOfNotNull(name, ask.model).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            val took = ask.durationMs
            if (took != null) {
                item(key = "took") { CaptionText(stringResource(R.string.ask_took, askSeconds(took)), Modifier.edgeTransform(this, spec)) }
            }
            item(key = "question") {
                Text(
                    ask.question,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).edgeTransform(this, spec),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            val answer = ask.answer
            if (ask.status == AskStatus.DONE && answer != null) {
                item(key = "answer") {
                    Card(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                    ) {
                        Text(answer, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                item(key = "error") {
                    CaptionText(errorMessage(ask.error ?: "bad_output"), Modifier.edgeTransform(this, spec), color = colors.error)
                }
            }
            item(key = "again") {
                FilledTonalButton(
                    onClick = onAgain,
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    label = { if (sending) SmallSpinner() else Text(stringResource(R.string.ask_again)) },
                )
            }
            item(key = "other") {
                FilledTonalButton(
                    onClick = onOther,
                    enabled = !sending,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, spec)
                        .minimumVerticalContentPadding(top = 0.dp, bottom = ButtonDefaults.minimumVerticalListContentPadding),
                    transformation = SurfaceTransformation(spec),
                    label = { Text(stringResource(other)) },
                )
            }
        }
    }
}

/** The question in one line above a spinner, and [Cancel] below. */
@Composable
private fun Thinking(question: String, onCancel: () -> Unit) {
    ScreenScaffold { contentPadding ->
        Column(
            Modifier.fillMaxSize().padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                question,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            CircularProgressIndicator()
            Text(stringResource(R.string.ask_thinking), style = MaterialTheme.typography.bodyMedium)
            CompactButton(onClick = onCancel, label = { Text(stringResource(R.string.ask_cancel)) })
        }
    }
}
