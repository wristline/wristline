package dev.wristline.watch.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextDefaults
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import dev.wristline.watch.BuildConfig
import dev.wristline.watch.R
import dev.wristline.watch.data.Bridge
import dev.wristline.watch.data.Conn
import kotlinx.coroutines.launch

@Composable
internal fun SettingsScreen(
    onAddress: () -> Unit,
    onRepair: () -> Unit,
    onPaired: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val prefs = Bridge.prefs
    val conn by Bridge.conn.collectAsStateWithLifecycle()
    var deviceName by remember { mutableStateOf(prefs.deviceName) }
    var monitoring by remember { mutableStateOf(prefs.monitoring) }
    var tokenNeedsAddress by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val editName = rememberTextInput(stringResource(R.string.settings_device_name)) {
        deviceName = it
        prefs.deviceName = it
    }
    val enterToken = rememberTextInput(stringResource(R.string.token_label)) {
        Bridge.useToken(prefs.baseUrl, it)
        onPaired()
    }
    SettingsContent(
        demo = conn is Conn.Demo,
        address = prefs.baseUrl,
        paired = prefs.isPaired,
        deviceName = deviceName,
        monitoring = monitoring,
        busy = busy,
        tokenNeedsAddress = tokenNeedsAddress,
        onAddress = onAddress,
        onDeviceName = editName,
        onMonitoring = {
            monitoring = it
            prefs.monitoring = it
        },
        onRepair = onRepair,
        onToken = { if (prefs.baseUrl.isEmpty()) tokenNeedsAddress = true else enterToken() },
        onDisconnect = { confirmDisconnect = true },
        onExitDemo = {
            Bridge.stopDemo()
            onSignedOut()
        },
    )
    AlertDialog(
        visible = confirmDisconnect,
        onDismissRequest = { confirmDisconnect = false },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    confirmDisconnect = false
                    busy = true
                    scope.launch {
                        Bridge.disconnect()
                        busy = false
                        onSignedOut()
                    }
                },
            )
        },
        title = { Text(stringResource(R.string.settings_disconnect_confirm)) },
    )
}

@Composable
internal fun SettingsContent(
    demo: Boolean,
    address: String,
    paired: Boolean,
    deviceName: String,
    monitoring: Boolean,
    busy: Boolean,
    tokenNeedsAddress: Boolean,
    onAddress: () -> Unit,
    onDeviceName: () -> Unit,
    onMonitoring: (Boolean) -> Unit,
    onRepair: () -> Unit,
    onToken: () -> Unit,
    onDisconnect: () -> Unit,
    onExitDemo: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val colors = MaterialTheme.colorScheme
    ScreenScaffold(scrollState = listState) { contentPadding ->
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
                ) { Text(stringResource(R.string.settings_title)) }
            }
            if (demo) {
                item(key = "exitDemo") {
                    Button(
                        onClick = onExitDemo,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        label = { Text(stringResource(R.string.settings_exit_demo)) },
                    )
                }
            } else {
                item(key = "address") {
                    FilledTonalButton(
                        onClick = onAddress,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        label = { Text(stringResource(R.string.address_title)) },
                        secondaryLabel = {
                            Text(
                                address.removePrefix("https://").ifEmpty { stringResource(R.string.settings_not_set) },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
            item(key = "name") {
                FilledTonalButton(
                    onClick = onDeviceName,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    label = { Text(stringResource(R.string.settings_device_name)) },
                    secondaryLabel = { Text(deviceName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
            if (!demo) {
                item(key = "monitoring") {
                    SwitchButton(
                        checked = monitoring,
                        onCheckedChange = onMonitoring,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        label = { Text(stringResource(R.string.settings_monitoring)) },
                        secondaryLabel = { Text(stringResource(R.string.settings_monitoring_detail), maxLines = 2) },
                    )
                }
                if (address.isNotEmpty()) {
                    item(key = "repair") {
                        FilledTonalButton(
                            onClick = onRepair,
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                            transformation = SurfaceTransformation(spec),
                            label = { Text(stringResource(R.string.conn_repair)) },
                        )
                    }
                }
                item(key = "token") {
                    FilledTonalButton(
                        onClick = onToken,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        label = { Text(stringResource(R.string.settings_token)) },
                    )
                }
                if (tokenNeedsAddress) {
                    item(key = "tokenError") {
                        CaptionText(
                            stringResource(R.string.settings_token_needs_address),
                            Modifier.edgeTransform(this, spec).animateItem(),
                            color = colors.error,
                        )
                    }
                }
                if (paired) {
                    item(key = "disconnect") {
                        Button(
                            onClick = onDisconnect,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                            transformation = SurfaceTransformation(spec),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.errorContainer, contentColor = colors.onErrorContainer),
                            label = { if (busy) SmallSpinner() else Text(stringResource(R.string.settings_disconnect)) },
                        )
                    }
                }
            }
            item(key = "version") {
                CaptionText(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), Modifier.edgeTransform(this, spec))
            }
            item(key = "privacy") {
                CaptionText(
                    stringResource(R.string.settings_privacy) + "\n" + stringResource(R.string.privacy_url).removePrefix("https://"),
                    Modifier
                        .edgeTransform(this, spec)
                        .minimumVerticalContentPadding(top = 0.dp, bottom = TextDefaults.minimumBottomListContentPadding),
                )
            }
        }
    }
}
