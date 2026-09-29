package dev.wristline.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CheckboxButton
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import dev.wristline.watch.R
import dev.wristline.watch.data.Answers
import dev.wristline.watch.data.Bridge
import dev.wristline.watch.data.Conn
import dev.wristline.watch.data.Decision
import dev.wristline.watch.data.Option
import dev.wristline.watch.data.PERMISSION_QUESTION
import dev.wristline.watch.data.PendingRequest
import dev.wristline.watch.data.Question
import dev.wristline.watch.data.RequestKind
import dev.wristline.watch.data.Sent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long "Already handled" stays before the screen closes itself. */
private const val HANDLED_MS = 1_500L

@Composable
internal fun RequestScreen(requestId: String, onDone: () -> Unit) {
    val requests by Bridge.requests.collectAsStateWithLifecycle()
    val sessions by Bridge.sessions.collectAsStateWithLifecycle()
    val conn by Bridge.conn.collectAsStateWithLifecycle()
    val request = remember(requests, requestId) { requests.firstOrNull { it.id == requestId } }
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var answered by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Gone from an up-to-date list without our answer: answered in the terminal, timed out, or 409.
    val handled = request == null && !answered && !sending && (conn is Conn.Online || conn is Conn.Demo)
    LaunchedEffect(handled) {
        if (handled) {
            delay(HANDLED_MS)
            onDone()
        }
    }

    when {
        request != null -> {
            val session = sessions.firstOrNull { it.id == request.sessionId }
            RequestContent(
                request = request,
                sessionTitle = session?.let { sessionTitle(it) },
                sending = sending,
                error = error,
                onAnswer = { answers ->
                    sending = true
                    error = null
                    scope.launch {
                        when (val result = Bridge.answer(request.id, answers)) {
                            Sent.Ok -> {
                                answered = true
                                onDone()
                            }
                            Sent.Unreachable -> error = "unreachable"
                            is Sent.Refused -> error = result.code
                        }
                        sending = false
                    }
                },
            )
        }
        handled -> StatusScreen(stringResource(R.string.error_already_resolved), spinner = false)
        !answered -> StatusScreen(stringResource(R.string.request_waiting), spinner = true)
    }
}

@Composable
internal fun StatusScreen(text: String, spinner: Boolean) {
    ScreenScaffold { contentPadding ->
        Column(
            Modifier.fillMaxSize().padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (spinner) SmallSpinner()
            BodyText(text)
        }
    }
}

@Composable
internal fun RequestContent(
    request: PendingRequest,
    sessionTitle: String?,
    sending: Boolean,
    error: String?,
    onAnswer: (Answers) -> Unit,
) {
    if (request.kind == RequestKind.PERMISSION) {
        val question = request.questions.firstOrNull { it.id == PERMISSION_QUESTION } ?: request.questions.firstOrNull()
        PermissionContent(request, question, sessionTitle, sending, error, onAnswer)
    } else {
        QuestionsContent(request, sessionTitle, sending, error, onAnswer)
    }
}

/** Tool name, the command or plan it wants to run, then Allow / Always / Deny / On PC. */
@Composable
private fun PermissionContent(
    request: PendingRequest,
    question: Question?,
    sessionTitle: String?,
    sending: Boolean,
    error: String?,
    onAnswer: (Answers) -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val colors = MaterialTheme.colorScheme
    ScreenScaffold(scrollState = listState) { contentPadding ->
        // Default rotary (fling with haptics): the body can be long and is read continuously.
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            requestHeader(request.title, sessionTitle, spec)
            if (question != null && question.text.isNotEmpty()) {
                item(key = "body") {
                    // Not edge-transformed: a long body would be drawn through a full-size offscreen layer.
                    Text(
                        question.text,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            errorItem(error, spec)
            if (question != null) {
                items(question.options, key = { it.id }) { option ->
                    val answer = { onAnswer(mapOf(question.id to listOf(option.id))) }
                    val modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, spec)
                        .minimumVerticalContentPadding(top = 0.dp, bottom = ButtonDefaults.minimumVerticalListContentPadding)
                    val label: @Composable RowScope.() -> Unit = { Text(decisionLabel(option), maxLines = 1) }
                    val secondary: (@Composable RowScope.() -> Unit)? =
                        option.description?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                    val transformation = SurfaceTransformation(spec)
                    when (option.id) {
                        Decision.ALLOW -> Button(
                            onClick = answer, modifier = modifier, enabled = !sending, transformation = transformation,
                            secondaryLabel = secondary, label = label,
                        )
                        Decision.DENY -> Button(
                            onClick = answer, modifier = modifier, enabled = !sending, transformation = transformation,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.errorContainer, contentColor = colors.onErrorContainer),
                            secondaryLabel = secondary, label = label,
                        )
                        Decision.DEFER -> OutlinedButton(
                            onClick = answer, modifier = modifier, enabled = !sending, transformation = transformation,
                            secondaryLabel = secondary, label = label,
                        )
                        else -> FilledTonalButton(
                            onClick = answer, modifier = modifier, enabled = !sending, transformation = transformation,
                            secondaryLabel = secondary, label = label,
                        )
                    }
                }
            }
        }
    }
}

/** One question at a time: single choice answers on tap, multiple choice toggles then [Next]. */
@Composable
private fun QuestionsContent(
    request: PendingRequest,
    sessionTitle: String?,
    sending: Boolean,
    error: String?,
    onAnswer: (Answers) -> Unit,
) {
    var index by rememberSaveable(request.id) { mutableIntStateOf(0) }
    val answers = remember(request.id) { mutableStateMapOf<String, List<String>>() }
    val question = request.questions.getOrNull(index) ?: return
    val last = index >= request.questions.lastIndex
    val choose = { ids: List<String> ->
        answers[question.id] = ids
        if (last) onAnswer(answers.toMap()) else index++
    }
    key(request.id, index) {
        val listState = rememberTransformingLazyColumnState()
        val spec = rememberTransformationSpec()
        val selected = remember { mutableStateListOf<String>() }
        val progress = if (request.questions.size > 1) {
            stringResource(R.string.request_progress, index + 1, request.questions.size)
        } else {
            null
        }
        val list: @Composable BoxScope.(PaddingValues) -> Unit = { contentPadding ->
            TransformingLazyColumn(
                state = listState,
                contentPadding = contentPadding,
                rotaryScrollableBehavior = RotaryScrollableDefaults.snapBehavior(listState),
            ) {
                requestHeader(question.header ?: request.title, listOfNotNull(progress, sessionTitle).joinToString(" · "), spec)
                if (question.text.isNotEmpty()) {
                    item(key = "text") { BodyText(question.text, Modifier.edgeTransform(this, spec)) }
                }
                errorItem(error, spec)
                items(question.options, key = { it.id }) { option ->
                    val modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, spec)
                        .minimumVerticalContentPadding(top = 0.dp, bottom = ButtonDefaults.minimumVerticalListContentPadding)
                    val secondary: (@Composable RowScope.() -> Unit)? =
                        option.description?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
                    if (question.multi) {
                        CheckboxButton(
                            checked = option.id in selected,
                            onCheckedChange = { checked -> if (checked) selected += option.id else selected -= option.id },
                            modifier = modifier,
                            enabled = !sending,
                            transformation = SurfaceTransformation(spec),
                            secondaryLabel = secondary,
                            label = { Text(option.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        )
                    } else {
                        FilledTonalButton(
                            onClick = { choose(listOf(option.id)) },
                            modifier = modifier,
                            enabled = !sending,
                            transformation = SurfaceTransformation(spec),
                            secondaryLabel = secondary,
                            label = { Text(option.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        }
        if (question.multi) {
            ScreenScaffold(
                scrollState = listState,
                edgeButton = {
                    EdgeButton(
                        onClick = { choose(selected.toList()) },
                        buttonSize = EdgeButtonSize.Medium,
                        enabled = selected.isNotEmpty() && !sending,
                    ) {
                        if (sending) SmallSpinner() else Text(stringResource(if (last) R.string.action_send else R.string.action_next))
                    }
                },
                content = list,
            )
        } else {
            ScreenScaffold(scrollState = listState, content = list)
        }
    }
}

private fun TransformingLazyColumnScope.requestHeader(title: String, subtitle: String?, spec: TransformationSpec) {
    item(key = "title") {
        ListHeader(
            modifier = Modifier
                .fillMaxWidth()
                .transformedHeight(this, spec)
                .minimumVerticalContentPadding(ListHeaderDefaults.minimumTopListContentPadding),
            transformation = SurfaceTransformation(spec),
        ) { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
    if (!subtitle.isNullOrEmpty()) {
        item(key = "subtitle") { CaptionText(subtitle, Modifier.edgeTransform(this, spec)) }
    }
}

private fun TransformingLazyColumnScope.errorItem(error: String?, spec: TransformationSpec) {
    if (error == null) return
    item(key = "error") {
        CaptionText(
            errorMessage(error),
            Modifier.edgeTransform(this, spec).animateItem(),
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun decisionLabel(option: Option): String = when (option.id) {
    Decision.ALLOW -> stringResource(R.string.decision_allow)
    Decision.ALWAYS -> stringResource(R.string.decision_always)
    Decision.DENY -> stringResource(R.string.decision_deny)
    Decision.DEFER -> stringResource(R.string.decision_defer)
    else -> option.label
}
