package dev.wristline.watch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.compose.foundation.rememberAmbientModeManager
import dev.wristline.watch.data.AddressResult
import dev.wristline.watch.data.Bridge
import dev.wristline.watch.data.Holder
import dev.wristline.watch.data.Sent
import dev.wristline.watch.data.normalizeAddress
import dev.wristline.watch.ui.LocalAmbient
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Screens to open once, from a notification tap ([notificationTarget]). */
    private var openTarget by mutableStateOf<List<Route>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate: the launch theme gives way to the app's (see themes.xml).
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Bridge.init(this)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            // Wear OS 6 and later; older watches pause the activity when the screen dims.
            val ambientManager = if (hasAmbientApi) rememberAmbientModeManager() else null
            val ambient = ambientManager?.currentAmbientMode is AmbientMode.Ambient
            LaunchedEffect(ambient) { onAmbient(ambient) }
            CompositionLocalProvider(LocalAmbient provides ambient) {
                App(openTarget = openTarget, onOpened = { openTarget = null })
            }
        }
    }

    /**
     * The ambient API says the app went [ambient] (always-on, dimmed) or came back. Still resumed,
     * the activity leaves as it does on pause, and comes back as it does on resume.
     */
    private fun onAmbient(ambient: Boolean) {
        if (ambient == Bridge.ambient) return
        Log.i("Wristline", "ambient=$ambient resumed=${lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)}")
        Bridge.ambient = ambient
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (ambient) Bridge.toBackground() else Bridge.foreground = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Bridge.acquire(Holder.ACTIVITY)
        // Restarts monitoring after a reboot or after the system stopped it (there is no boot receiver).
        MonitorService.sync(this)
    }

    override fun onResume() {
        super.onResume()
        Bridge.toForeground()
    }

    override fun onPause() {
        Bridge.toBackground()
        super.onPause()
    }

    override fun onStop() {
        Bridge.release(Holder.ACTIVITY)
        super.onStop()
    }

    private fun handleIntent(intent: Intent) {
        // A task first opened from a notification keeps that intent; Recents replays it later.
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
            notificationTarget(intent.getStringExtra(EXTRA_REQUEST_ID), intent.getStringExtra(EXTRA_SESSION_ID), intent.getBooleanExtra(EXTRA_HOME, false))
                ?.let { openTarget = it }
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
            // A new instance has no saved back stack and opens on the session list; recreate()
            // would restore the Welcome screen.
            if (result == Sent.Ok) {
                startActivity(Intent(this@MainActivity, MainActivity::class.java))
                finish()
            }
        }
    }

    companion object {
        /** The ambient API ([rememberAmbientModeManager]) needs the Wear SDK, which Wear OS 6 and later have. */
        private val hasAmbientApi = runCatching { Class.forName("com.google.wear.Sdk") }.isSuccess

        /** Extra carrying a PendingRequest id; opens the request screen (over its session, given [EXTRA_SESSION_ID]). */
        const val EXTRA_REQUEST_ID = "requestId"

        /** Extra carrying a session id (alert notifications); opens the session. */
        const val EXTRA_SESSION_ID = "sessionId"

        /** Extra of the monitoring card's intent (through [OpenActivity]); opens the session list, whatever was open. */
        const val EXTRA_HOME = "home"

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
