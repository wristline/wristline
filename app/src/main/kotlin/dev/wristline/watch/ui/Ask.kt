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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import dev.wristline.watch.R
import dev.wristline.watch.data.Ask
import dev.wristline.watch.data.AskSent
import dev.wristline.watch.data.AskStatus
import dev.wristline.watch.data.Bridge
import dev.wristline.watch.data.Conn
import dev.wristline.watch.data.ProviderId
import dev.wristline.watch.data.isoToMillis
import dev.wristline.watch.data.thread
import java.util.Locale
import kotlinx.coroutines.launch

/** Seconds with one decimal, as the answer header shows the time the CLI took: 1389 ms is `1.4`. */
internal fun askSeconds(durationMs: Long): String = String.format(Locale.ROOT, "%.1f", durationMs / 1000.0)

/** The provider a question goes to when the user asks with the other one. */
internal fun otherProvider(provider: String): String =
    if (provider == ProviderId.CODEX) ProviderId.CLAUDE_CODE else ProviderId.CODEX

/**
 * The conversation [askId] belongs to, oldest first, or null when the bridge no longer has the
 * ask. [asks] is newest first, so among equal times the later-listed ask is the older one.
 */
internal fun askThread(asks: List<Ask>, askId: String): List<Ask>? {
    val ask = asks.firstOrNull { it.id == askId } ?: return null
    return asks.asReversed().filter { it.thread == ask.thread }.sortedBy { isoToMillis(it.createdAt) ?: 0L }
}

/** The icon buttons under the newest answer. */
private val ACTION_SIZE = 40.dp
private val ACTION_GAP = 8.dp

/**
 * The Ask button: speech (or typing) in, a confirm dialog with the provider badge to toggle
 * Claude and Codex, then `POST /api/ask`. [onStarted] gets the new askId. The returned lambda
 * starts the flow; the dialog and any refusal toast are composed here.
 *
 * With [threadId] the question is a follow-up: it starts on [threadProvider] and continues that
 * conversation; switched to the other provider, which has no such conversation, it starts a new
 * thread, and the switch is not remembered as the default.
 */
@Composable
internal fun rememberQuickAsk(
    onStarted: (String) -> Unit,
    threadId: String? = null,
    threadProvider: String? = null,
): () -> Unit {
    val context = LocalContext.current
    val prefs = Bridge.prefs
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var provider by remember(threadProvider) { mutableStateOf(threadProvider ?: prefs.askProvider) }
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
                        val continued = threadId?.takeIf { provider == threadProvider }
                        when (val sent = Bridge.ask(provider, draft, continued)) {
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
                        if (threadId == null) prefs.askProvider = provider
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
 * The conversation [askId] belongs to, newest at the bottom. Under the newest answer: ask the
 * newest question again (a new thread, same provider), ask it of the other provider (a new
 * thread), read the answer aloud, and a follow-up that continues this thread. A new thread swaps
 * this screen for its own ([onReplaced]). Leaving while a question is still running cancels it.
 */
@Composable
internal fun AskScreen(askId: String, onReplaced: (String) -> Unit) {
    val asks by Bridge.asks.collectAsStateWithLifecycle()
    val conn by Bridge.conn.collectAsStateWithLifecycle()
    val thread = remember(asks, askId) { askThread(asks, askId) }
    val newest = thread?.lastOrNull()
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val reader = rememberReader()

    val running by rememberUpdatedState(newest?.takeIf { it.status == AskStatus.RUNNING }?.id)
    DisposableEffect(askId) {
        onDispose { running?.let { Bridge.cancelAsk(it) } }
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
        val current = newest
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

    // A follow-up joins this thread; one sent to the other provider starts a thread, shown instead.
    val threadId = thread?.firstOrNull()?.thread
    val followUp = rememberQuickAsk(
        onStarted = { id -> if (Bridge.asks.value.firstOrNull { it.id == id }?.thread != threadId) onReplaced(id) },
        threadId = threadId,
        threadProvider = newest?.provider,
    )

    if (thread == null && conn !is Conn.Online && conn !is Conn.Demo) {
        // The list is fetched after the socket comes up; until then this may still be answered.
        StatusScreen(stringResource(R.string.conn_connecting), spinner = true)
        return
    }
    AskContent(
        thread = thread,
        sending = sending,
        speaking = reader.speaking,
        onCancel = { running?.let { Bridge.cancelAsk(it) } },
        onAgain = { newest?.let { resend(it.provider) } },
        onOther = { newest?.let { resend(otherProvider(it.provider)) } },
        onSpeak = { newest?.answer?.let { reader.toggle(it) } },
        onFollowUp = followUp,
    )
}

/** [thread] null or empty: the bridge no longer has it (restarted, expired or deleted). */
@Composable
internal fun AskContent(
    thread: List<Ask>?,
    sending: Boolean,
    speaking: Boolean,
    onCancel: () -> Unit,
    onAgain: () -> Unit,
    onOther: () -> Unit,
    onSpeak: () -> Unit,
    onFollowUp: () -> Unit,
) {
    if (thread.isNullOrEmpty()) {
        StatusScreen(stringResource(R.string.ask_gone), spinner = false)
        return
    }
    val newest = thread.last()
    if (thread.size == 1 && newest.status == AskStatus.RUNNING) {
        Thinking(newest.question, onCancel)
        return
    }
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val name = providerLabel(newest.provider)
    // A follow-up is read from its question down: when the thread grows, the newest question is
    // brought to the top of the screen.
    val newestIndex = remember(thread) { 1 + thread.dropLast(1).sumOf { it.itemCount } }
    LaunchedEffect(thread.size) {
        if (thread.size > 1) listState.scrollToItem(newestIndex)
    }
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
                        ProviderBadge(newest.provider)
                        Text(listOfNotNull(name, newest.model).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            thread.forEach { askItems(it, spec, onCancel) }
            if (newest.status != AskStatus.RUNNING) {
                item(key = "actions") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .edgeTransform(this, spec)
                            .minimumVerticalContentPadding(top = 0.dp, bottom = ButtonDefaults.minimumVerticalListContentPadding),
                        horizontalArrangement = Arrangement.spacedBy(ACTION_GAP, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val iconSize = IconButtonDefaults.iconSizeFor(ACTION_SIZE)
                        FilledTonalIconButton(onClick = onAgain, enabled = !sending, modifier = Modifier.size(ACTION_SIZE)) {
                            if (sending) SmallSpinner() else Icon(painterResource(R.drawable.ic_replay), stringResource(R.string.ask_again), Modifier.size(iconSize))
                        }
                        val other = otherProvider(newest.provider)
                        val askOther = stringResource(if (other == ProviderId.CODEX) R.string.ask_other_codex else R.string.ask_other_claude)
                        FilledTonalIconButton(
                            onClick = onOther,
                            enabled = !sending,
                            modifier = Modifier.size(ACTION_SIZE).clearAndSetSemantics {
                                contentDescription = askOther
                                role = Role.Button
                            },
                        ) {
                            ProviderBadge(other, size = 20.dp)
                        }
                        FilledTonalIconButton(onClick = onSpeak, enabled = newest.answer != null, modifier = Modifier.size(ACTION_SIZE)) {
                            if (speaking) {
                                Icon(painterResource(R.drawable.ic_stop), stringResource(R.string.ask_stop_reading), Modifier.size(iconSize))
                            } else {
                                Icon(painterResource(R.drawable.ic_speaker), stringResource(R.string.ask_read_aloud), Modifier.size(iconSize))
                            }
                        }
                        FilledTonalIconButton(onClick = onFollowUp, enabled = !sending, modifier = Modifier.size(ACTION_SIZE)) {
                            Icon(painterResource(R.drawable.ic_mic), stringResource(R.string.ask_follow_up), Modifier.size(iconSize))
                        }
                    }
                }
            }
        }
    }
}

/** How many list items [askItems] adds for this ask. */
private val Ask.itemCount: Int get() = if (durationMs != null) 3 else 2

/** The question, then its answer, error or spinner, then how long it took when known. */
private fun TransformingLazyColumnScope.askItems(ask: Ask, spec: TransformationSpec, onCancel: () -> Unit) {
    item(key = "question/${ask.id}") {
        Text(
            ask.question,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).edgeTransform(this, spec),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
    val answer = ask.answer
    when {
        ask.status == AskStatus.RUNNING -> item(key = "running/${ask.id}") {
            Row(
                Modifier.fillMaxWidth().edgeTransform(this, spec),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallSpinner()
                CompactButton(onClick = onCancel, label = { Text(stringResource(R.string.ask_cancel)) })
            }
        }
        ask.status == AskStatus.DONE && answer != null -> item(key = "answer/${ask.id}") {
            Card(
                onClick = {},
                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                transformation = SurfaceTransformation(spec),
            ) {
                Text(answer, style = MaterialTheme.typography.bodyMedium)
            }
        }
        else -> item(key = "error/${ask.id}") {
            CaptionText(errorMessage(ask.error ?: "bad_output"), Modifier.edgeTransform(this, spec), color = MaterialTheme.colorScheme.error)
        }
    }
    val took = ask.durationMs
    if (took != null) {
        item(key = "took/${ask.id}") { CaptionText(stringResource(R.string.ask_took, askSeconds(took)), Modifier.edgeTransform(this, spec)) }
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
