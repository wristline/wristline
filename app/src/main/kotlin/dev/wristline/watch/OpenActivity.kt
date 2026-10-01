package dev.wristline.watch

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Touch target of the monitoring Ongoing Activity. An Ongoing Activity keeps the task it opens on
 * screen in ambient instead of letting the system return to the watch face
 * (https://developer.android.com/training/wearables/always-on). The Wear OS 7 system UI counts a
 * task as the Ongoing Activity's when the touch intent's activity is the task's top or base
 * activity, or shares its task affinity and package; a touch intent that is not an activity (a
 * broadcast or service) counts every task of the package. This activity shows nothing, opens
 * [MainActivity] in MainActivity's own task and finishes, so it is never in that task, and
 * MainActivity's empty taskAffinity keeps the affinities apart.
 */
class OpenActivity : Activity() {
    // WearRecents: NEW_TASK with the launcher's intent resumes the one existing task, as a
    // launcher tap does; no second Recents entry comes of it.
    @SuppressLint("WearRecents")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // NEW_TASK: MainActivity resumes its own task (matched by component, as from the launcher)
        // instead of stacking on this one.
        startActivity(MainActivity.openIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
