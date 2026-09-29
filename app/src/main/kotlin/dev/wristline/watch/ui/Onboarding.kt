package dev.wristline.watch.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.PickerGroup
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.rememberPickerState
import dev.wristline.watch.R
import dev.wristline.watch.data.AddressError
import dev.wristline.watch.data.AddressResult
import dev.wristline.watch.data.Bridge
import dev.wristline.watch.data.Probe
import dev.wristline.watch.data.Sent
import dev.wristline.watch.data.normalizeAddress
import kotlinx.coroutines.launch

// ---- welcome ----

@Composable
internal fun WelcomeScreen(onSetUp: () -> Unit, onDemo: () -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(onClick = onSetUp, buttonSize = EdgeButtonSize.Medium) { Text(stringResource(R.string.welcome_setup)) }
        },
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            rotaryScrollableBehavior = RotaryScrollableDefaults.snapBehavior(listState),
        ) {
            item(key = "title") {
                ListHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, spec)
                        .minimumVerticalContentPadding(ListHeaderDefaults.minimumTopListContentPadding),
                    transformation = SurfaceTransformation(spec),
                ) { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge) }
            }
            item(key = "tagline") {
                BodyText(stringResource(R.string.welcome_tagline), Modifier.edgeTransform(this, spec))
            }
            item(key = "demo") {
                OutlinedButton(
                    onClick = onDemo,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    label = { Text(stringResource(R.string.welcome_demo)) },
                )
            }
        }
    }
}

// ---- notification permission ----

@Composable
internal fun NotifyScreen(onNext: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onNext() }
    MessageScreen(
        title = stringResource(R.string.notify_title),
        body = stringResource(R.string.notify_body),
        action = stringResource(R.string.notify_continue),
        onAction = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
    )
}

/** Title, a paragraph and one EdgeButton action. */
@Composable
internal fun MessageScreen(title: String, body: String, action: String, onAction: () -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = { EdgeButton(onClick = onAction, buttonSize = EdgeButtonSize.Medium) { Text(action) } },
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            rotaryScrollableBehavior = RotaryScrollableDefaults.snapBehavior(listState),
        ) {
            item(key = "title") {
                ListHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, spec)
                        .minimumVerticalContentPadding(ListHeaderDefaults.minimumTopListContentPadding),
                    transformation = SurfaceTransformation(spec),
                ) { Text(title) }
            }
            item(key = "body") { BodyText(body, Modifier.edgeTransform(this, spec)) }
        }
    }
}

@Composable
internal fun BodyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
}

// ---- address ----

/** Progress of finding the bridge at the entered address. */
internal sealed interface AddressState {
    data object Idle : AddressState
    data object Searching : AddressState
    data object Found : AddressState
    data class Invalid(val reason: AddressError) : AddressState
    data class Failed(val probe: Probe) : AddressState
}

@Composable
internal fun AddressScreen(onFound: () -> Unit, onPairedWithToken: () -> Unit) {
    var address by rememberSaveable { mutableStateOf(Bridge.prefs.baseUrl) }
    var state by remember { mutableStateOf<AddressState>(AddressState.Idle) }
    val scope = rememberCoroutineScope()
    val enterAddress = rememberTextInput(stringResource(R.string.address_title)) { text ->
        when (val result = normalizeAddress(text)) {
            is AddressResult.Error -> state = AddressState.Invalid(result.reason)
            is AddressResult.Ok -> {
                address = result.url
                state = AddressState.Searching
                scope.launch {
                    val probe = Bridge.probe(result.url)
                    if (probe == Probe.FOUND) Bridge.useAddress(result.url)
                    state = if (probe == Probe.FOUND) AddressState.Found else AddressState.Failed(probe)
                }
            }
        }
    }
    val enterToken = rememberTextInput(stringResource(R.string.token_label)) { token ->
        Bridge.useToken(address, token)
        onPairedWithToken()
    }
    AddressContent(address, state, onEnter = enterAddress, onNext = onFound, onToken = enterToken)
}

@Composable
internal fun AddressContent(
    address: String,
    state: AddressState,
    onEnter: () -> Unit,
    onNext: () -> Unit,
    onToken: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val found = state == AddressState.Found
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(
                onClick = if (found) onNext else onEnter,
                buttonSize = EdgeButtonSize.Medium,
                enabled = state != AddressState.Searching,
            ) {
                Text(stringResource(if (found) R.string.action_next else R.string.address_enter))
            }
        },
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            rotaryScrollableBehavior = RotaryScrollableDefaults.snapBehavior(listState),
        ) {
            item(key = "title") {
                ListHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, spec)
                        .minimumVerticalContentPadding(ListHeaderDefaults.minimumTopListContentPadding),
                    transformation = SurfaceTransformation(spec),
                ) { Text(stringResource(R.string.address_title)) }
            }
            if (address.isNotEmpty()) {
                item(key = "address") {
                    Text(
                        address.removePrefix("https://"),
                        modifier = Modifier.fillMaxWidth().edgeTransform(this, spec),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item(key = "status") {
                AnimatedContent(targetState = state, modifier = Modifier.fillMaxWidth().edgeTransform(this, spec), label = "probe") {
                    AddressStatus(it)
                }
            }
            if (found) {
                item(key = "change") {
                    OutlinedButton(
                        onClick = onEnter,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).animateItem(),
                        transformation = SurfaceTransformation(spec),
                        label = { Text(stringResource(R.string.address_change)) },
                    )
                }
                item(key = "token") {
                    OutlinedButton(
                        onClick = onToken,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).animateItem(),
                        transformation = SurfaceTransformation(spec),
                        label = { Text(stringResource(R.string.token_use)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddressStatus(state: AddressState) {
    val colors = MaterialTheme.colorScheme
    when (state) {
        AddressState.Idle -> CaptionText(stringResource(R.string.address_hint))
        AddressState.Searching -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallSpinner()
            Text(stringResource(R.string.address_searching), style = MaterialTheme.typography.labelSmall)
        }
        AddressState.Found -> CaptionText(stringResource(R.string.address_found), color = colors.primary)
        is AddressState.Invalid -> CaptionText(
            stringResource(
                when (state.reason) {
                    AddressError.EMPTY -> R.string.address_error_empty
                    AddressError.INSECURE -> R.string.address_error_insecure
                    AddressError.INVALID -> R.string.address_error_invalid
                },
            ),
            color = colors.error,
        )
        is AddressState.Failed -> CaptionText(
            when (state.probe) {
                Probe.RATE_LIMITED -> stringResource(R.string.error_rate_limited)
                Probe.UNREACHABLE -> stringResource(R.string.probe_unreachable)
                else -> stringResource(R.string.probe_not_bridge)
            },
            color = colors.error,
        )
    }
}

// ---- pairing code ----

private const val CODE_LENGTH = 6

@Composable
internal fun CodeScreen(onPaired: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    CodeContent(busy = busy, error = error) { code ->
        busy = true
        error = null
        scope.launch {
            when (val result = Bridge.pair(Bridge.prefs.baseUrl, code)) {
                Sent.Ok -> onPaired()
                Sent.Unreachable -> error = "unreachable"
                is Sent.Refused -> error = result.code
            }
            busy = false
        }
    }
}

/** Six 0–9 pickers; the selected one follows taps and receives rotary input. */
@Composable
internal fun CodeContent(busy: Boolean, error: String?, onSubmit: (String) -> Unit) {
    val pickers = List(CODE_LENGTH) { rememberPickerState(initialNumberOfOptions = 10) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val resources = LocalResources.current
    ScreenScaffold { contentPadding ->
        // The column fills the space above the EdgeButton, which hugs the bottom edge.
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = contentPadding.calculateTopPadding(), bottom = EDGE_BUTTON_SMALL_HEIGHT),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val message = when (error) {
                null -> null
                "not_found" -> stringResource(R.string.error_no_pairing)
                else -> errorMessage(error)
            }
            Text(
                message ?: stringResource(R.string.code_title),
                color = if (message != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = if (message != null) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                // The longest error strings need three lines at this width.
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
            Spacer(Modifier.height(4.dp))
            // No auto-centering: the six pickers fit on screen, and centering the selected one
            // would push the row past the round edge.
            PickerGroup(
                selectedPickerState = pickers[selected],
                modifier = Modifier.fillMaxWidth().height(88.dp),
                autoCenter = false,
            ) {
                pickers.forEachIndexed { index, state ->
                    PickerGroupItem(
                        pickerState = state,
                        selected = selected == index,
                        onSelected = { selected = index },
                        modifier = Modifier.width(30.dp),
                        // Read lazily: selectedOptionIndex changes on every scroll frame.
                        contentDescription = { resources.getString(R.string.code_digit, index + 1, state.selectedOptionIndex) },
                    ) { option, _ ->
                        Text("$option", style = MaterialTheme.typography.numeralSmall)
                    }
                }
            }
        }
        EdgeButton(
            onClick = { onSubmit(pickers.joinToString("") { it.selectedOptionIndex.toString() }) },
            modifier = Modifier.align(Alignment.BottomCenter),
            buttonSize = EdgeButtonSize.Small,
            enabled = !busy,
        ) {
            if (busy) SmallSpinner() else Text(stringResource(R.string.code_pair))
        }
    }
}

/** Height of [EdgeButtonSize.Small]. */
private val EDGE_BUTTON_SMALL_HEIGHT = 56.dp
