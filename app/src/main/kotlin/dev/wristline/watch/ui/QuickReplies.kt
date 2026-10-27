package dev.wristline.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ConfirmationDialogDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SuccessConfirmationDialog
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.confirmationDialogCurvedText
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import dev.wristline.watch.R
import dev.wristline.watch.data.Bridge
import dev.wristline.watch.data.QUICK_REPLIES_MAX
import dev.wristline.watch.data.QUICK_REPLY_MAX_CHARS
import dev.wristline.watch.data.Sent
import dev.wristline.watch.data.quickRepliesOf
import dev.wristline.watch.data.sentHaptic
import kotlinx.coroutines.launch

/**
 * The quick replies for session [sessionId]: a tap sends one as the voice prompt goes out (no
 * confirm step: the phrase is already known), then "Sent" for [CONFIRMATION_MS] and [onSent]
 * closes the screen. A blocked session shows why instead of the replies.
 */
@Composable
internal fun QuickRepliesScreen(sessionId: String, onSent: () -> Unit) {
    val sessions by Bridge.sessions.collectAsStateWithLifecycle()
    val session = remember(sessions, sessionId) { sessions.firstOrNull { it.id == sessionId } }
    // Read once: only Settings edits them.
    val replies = remember { Bridge.prefs.quickReplies }
    val scope = rememberCoroutineScope()
    val touch = rememberTouchHaptics()
    var sending by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(false) }
    QuickRepliesContent(
        replies = replies,
        // A session gone from the list is blocked as the session screen blocks it.
        blockCode = if (session == null) "not_live" else session.promptBlock,
        sending = sending,
        error = error,
        onSend = { text ->
            sending = text
            error = null
            scope.launch {
                val result = Bridge.prompt(sessionId, text)
                touch(sentHaptic(result))
                when (result) {
                    Sent.Ok -> sent = true
                    Sent.Unreachable -> error = "unreachable"
                    is Sent.Refused -> error = result.code
                }
                sending = null
            }
        },
    )
    val style = ConfirmationDialogDefaults.curvedTextStyle
    val text = stringResource(R.string.detail_sent)
    // Once, whichever comes first: the timeout, a swipe, back or a tap.
    val close = {
        if (sent) {
            sent = false
            onSent()
        }
    }
    SuccessConfirmationDialog(
        visible = sent,
        onDismissRequest = close,
        curvedText = { confirmationDialogCurvedText(text, style) },
        modifier = Modifier.clickable(interactionSource = null, indication = null, onClick = close),
        durationMillis = CONFIRMATION_MS,
    )
}

@Composable
internal fun QuickRepliesContent(
    replies: List<String>,
    /** The session's prompt block, if any: shown in place of the replies. */
    blockCode: String?,
    /** The reply being sent; all wait meanwhile. */
    sending: String?,
    error: String?,
    onSend: (String) -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val colors = MaterialTheme.colorScheme
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            flingBehavior = TransformingLazyColumnDefaults.snapFlingBehavior(listState),
            rotaryScrollableBehavior = RotaryScrollableDefaults.snapBehavior(listState),
        ) {
            item(key = "title") { Header(stringResource(R.string.settings_quick_replies), spec) }
            if (blockCode != null) {
                item(key = "block") { CaptionText(errorMessage(blockCode), Modifier.edgeTransform(this, spec).minListItemHeight()) }
                return@TransformingLazyColumn
            }
            if (error != null) {
                item(key = "error") {
                    CaptionText(errorMessage(error), Modifier.edgeTransform(this, spec).animateItemCalmly(this).minListItemHeight().announced(), color = colors.error)
                }
            }
            if (replies.isEmpty()) {
                item(key = "empty") { CaptionText(stringResource(R.string.quick_replies_empty), Modifier.edgeTransform(this, spec).minListItemHeight()) }
            }
            replies.forEachIndexed { i, reply ->
                item(key = "reply/$i") {
                    FilledTonalButton(
                        onClick = { onSend(reply) },
                        enabled = sending == null,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        label = { if (sending == reply) SmallSpinner() else Text(reply, maxLines = 3) },
                    )
                }
            }
            item(key = "end") { EndSpace(spec) }
        }
    }
}

/** Settings' editor of the quick replies: add (typed or spoken), delete, reorder, back to the defaults. */
@Composable
internal fun QuickRepliesEditScreen() {
    val prefs = Bridge.prefs
    var replies by remember { mutableStateOf(prefs.quickReplies) }
    val update = { list: List<String> ->
        prefs.quickReplies = list
        replies = prefs.quickReplies
    }
    val tooLong = pluralStringResource(R.plurals.quick_replies_too_long, QUICK_REPLY_MAX_CHARS, QUICK_REPLY_MAX_CHARS)
    val add = rememberTextInput(stringResource(R.string.settings_quick_replies)) { text ->
        if (text.length > QUICK_REPLY_MAX_CHARS) Failure.show(tooLong) else update(replies + text)
    }
    val defaults = quickRepliesOf(stringArrayResource(R.array.quick_replies_default).toList())
    QuickRepliesEditContent(
        replies = replies,
        edited = replies != defaults,
        onAdd = add,
        onMove = { i, by -> update(replies.toMutableList().apply { add(i + by, removeAt(i)) }) },
        onDelete = { i -> update(replies.filterIndexed { j, _ -> j != i }) },
        onReset = {
            prefs.resetQuickReplies()
            replies = prefs.quickReplies
        },
    )
}

/**
 * Add first (with the count, `4/8`; at [QUICK_REPLIES_MAX] it waits and says why), then each reply
 * with buttons under it, as the Accounts screen lists accounts: up, down and delete; Reset to defaults last.
 */
@Composable
internal fun QuickRepliesEditContent(
    replies: List<String>,
    /** The replies differ from the defaults: Reset has something to do. */
    edited: Boolean,
    onAdd: () -> Unit,
    onMove: (index: Int, by: Int) -> Unit,
    onDelete: (Int) -> Unit,
    onReset: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val colors = MaterialTheme.colorScheme
    val full = replies.size >= QUICK_REPLIES_MAX
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            flingBehavior = TransformingLazyColumnDefaults.snapFlingBehavior(listState),
            rotaryScrollableBehavior = RotaryScrollableDefaults.snapBehavior(listState),
        ) {
            item(key = "title") { Header(stringResource(R.string.settings_quick_replies), spec) }
            item(key = "add") {
                Button(
                    onClick = onAdd,
                    enabled = !full,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    label = { Text(stringResource(R.string.quick_replies_add)) },
                    secondaryLabel = { Text("${replies.size}/$QUICK_REPLIES_MAX") },
                )
            }
            if (full) {
                item(key = "max") {
                    CaptionText(
                        stringResource(R.string.quick_replies_max, QUICK_REPLIES_MAX),
                        Modifier.edgeTransform(this, spec).animateItemCalmly(this).minListItemHeight(),
                    )
                }
            }
            replies.forEachIndexed { i, reply ->
                item(key = "reply/$i") {
                    Text(
                        reply,
                        Modifier
                            .fillMaxWidth()
                            .edgeTransform(this, spec)
                            .clip(MaterialTheme.shapes.large)
                            .background(colors.surfaceContainer)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                    )
                }
                item(key = "reply/$i/edit") {
                    Row(
                        Modifier.fillMaxWidth().edgeTransform(this, spec),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    ) {
                        MoveButton(R.drawable.ic_arrow_up, stringResource(R.string.accounts_move_up, reply), enabled = i > 0) { onMove(i, -1) }
                        MoveButton(R.drawable.ic_arrow_down, stringResource(R.string.accounts_move_down, reply), enabled = i < replies.lastIndex) {
                            onMove(i, 1)
                        }
                        MoveButton(R.drawable.ic_delete, stringResource(R.string.quick_replies_delete, reply), enabled = true) { onDelete(i) }
                    }
                }
            }
            item(key = "reset") {
                FilledTonalButton(
                    onClick = onReset,
                    enabled = edited,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    label = { Text(stringResource(R.string.quick_replies_reset)) },
                )
            }
            item(key = "end") { EndSpace(spec) }
        }
    }
}
