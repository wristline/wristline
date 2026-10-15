package dev.wristline.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dev.wristline.watch.data.Bridge

/**
 * Monitoring comes back by itself after a reboot or an app update: without it, the Now Bar card
 * and the alerts stay away until the app is opened.
 */
internal fun shouldStartAtBoot(paired: Boolean, monitoring: Boolean, action: String?): Boolean =
    paired && monitoring && (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED)

/**
 * Starts [MonitorService] after BOOT_COMPLETED and MY_PACKAGE_REPLACED, both of which allow a
 * foreground service start from the background. connectedDevice is not among the types Android 15
 * keeps from starting at boot (https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed);
 * its prerequisite is met by CHANGE_NETWORK_STATE in the manifest. Not LOCKED_BOOT_COMPLETED: the
 * prefs are in credential-protected storage, unreadable before the first unlock.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Bridge.init(context)
        val prefs = Bridge.prefs
        // Like MonitorService.sync: without notifications it could not reach the user.
        if (!shouldStartAtBoot(prefs.isPaired, prefs.monitoring, intent.action) || !Notifier.enabled(context)) return
        Log.i(TAG, "Starting monitoring: ${intent.action}")
        try {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException; the next app start brings it back.
            Log.w(TAG, "Monitoring not allowed to start: ${intent.action}")
        }
    }

    private companion object {
        const val TAG = "Wristline"
    }
}
