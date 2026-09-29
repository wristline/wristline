package dev.wristline.watch

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
    private var requestId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Bridge.init(this)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { App(requestId = requestId, onRequestShown = { requestId = null }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Bridge.acquire()
    }

    override fun onStop() {
        Bridge.release()
        super.onStop()
    }

    private fun handleIntent(intent: Intent) {
        intent.getStringExtra(EXTRA_REQUEST_ID)?.let { requestId = it }
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
    }
}
