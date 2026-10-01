package dev.wristline.watch

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Touch target of the monitoring Ongoing Activity. An Ongoing Activity whose touch intent points
 * at an always-on activity keeps that activity on screen in ambient instead of letting the system
 * return to the watch face (https://developer.android.com/training/wearables/always-on). This one
 * is not always-on and shows nothing: it opens [MainActivity] and finishes.
 */
class OpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // NEW_TASK: MainActivity resumes its own task (matched by component, as from the launcher)
        // instead of stacking on this one.
        startActivity(MainActivity.openIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
