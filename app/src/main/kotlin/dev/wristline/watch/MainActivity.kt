package dev.wristline.watch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.wristline.watch.data.AddressResult
import dev.wristline.watch.data.Bridge
import dev.wristline.watch.data.Sent
import dev.wristline.watch.data.normalizeAddress
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Screen to open once, from a notification tap. */
    private var openRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Bridge.init(this)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { App(openRoute = openRoute, onOpened = { openRoute = null }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Bridge.acquire()
        // Restarts monitoring after a reboot or after the system stopped it (there is no boot receiver).
        MonitorService.sync(this)
    }

    override fun onResume() {
        super.onResume()
        Bridge.foreground = true
    }

    override fun onPause() {
        Bridge.toBackground()
        super.onPause()
    }

    override fun onStop() {
        Bridge.release()
        super.onStop()
    }

    private fun handleIntent(intent: Intent) {
        // A task first opened from a notification keeps that intent; Recents replays it later.
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
            intent.getStringExtra(EXTRA_REQUEST_ID)?.let { openRoute = Route.request(it) }
            intent.getStringExtra(EXTRA_SESSION_ID)?.let { openRoute = Route.session(it) }
        }
        if (BuildConfig.DEBUG) debugPair(intent)
    }

    /**
     * Debug builds only: pair from adb without the picker, e.g.
     * `adb shell am start -n dev.wristline.watch/.MainActivity --es debugPairUrl host.ts.net --es debugPairCode 123456`.
     */
    private fun debugPair(intent: Intent) {
        val address = intent.getStringExtra("debugPairUrl") ?: return
        val code = intent.getStringExtra("debugPairCode") ?: return
        val url = (normalizeAddress(address) as? AddressResult.Ok)?.url ?: return
        lifecycleScope.launch {
            val result = Bridge.pair(url, code)
            Log.i("Wristline", "debug pairing: $result")
            if (result == Sent.Ok) recreate()
        }
    }

    companion object {
        /** Extra carrying a PendingRequest id; opens the request screen. */
        const val EXTRA_REQUEST_ID = "requestId"

        /** Extra carrying a session id (alert notifications); opens the session. */
        const val EXTRA_SESSION_ID = "sessionId"

        /**
         * Opens the app from a notification. Same action and category as the launcher's intent, so
         * the existing task is resumed instead of a second MainActivity or Recents entry being
         * created; SINGLE_TOP hands the extras to [onNewIntent].
         */
        fun openIntent(context: Context): Intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClass(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
