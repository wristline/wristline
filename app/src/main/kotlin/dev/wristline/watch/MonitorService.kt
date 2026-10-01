package dev.wristline.watch

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import dev.wristline.watch.data.Bridge
import dev.wristline.watch.data.Conn
import dev.wristline.watch.data.Holder
import dev.wristline.watch.data.PendingRequest
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.SessionStatus
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Text of the monitoring ongoing activity, e.g. "2 running · 1 waiting": running sessions and
 * waiting requests, leaving out a count of zero; [idle] when both are zero.
 */
internal fun ongoingStatusText(
    sessions: List<Session>,
    requests: List<PendingRequest>,
    running: (Int) -> String,
    waiting: (Int) -> String,
    idle: String,
): String {
    val runningCount = sessions.count { it.status == SessionStatus.RUNNING }
    val parts = buildList {
        if (runningCount > 0) add(running(runningCount))
        if (requests.isNotEmpty()) add(waiting(requests.size))
    }
    return if (parts.isEmpty()) idle else parts.joinToString(" · ")
}

/**
 * Background monitoring: holds the bridge connection ([Bridge.acquire]) while the app is closed,
 * so requests and alerts arrive as notifications. Shown as an ongoing activity with live counts.
 *
 * While the watch is off the wrist (the off-body sensor, no permission needed) the hold is
 * released, so the socket closes and the bridge's presence for this watch goes false; the status
 * reads "Not worn" meanwhile. Putting the watch back on reconnects.
 *
 * Runs only while the user has it on in Settings ([dev.wristline.watch.data.Prefs.monitoring]).
 * There is no boot receiver: after a reboot, or when the system stops the service, it starts
 * again the next time Wristline is opened ([sync] from MainActivity). It stops itself, and turns
 * the setting off, when the pairing is gone (Unauthorized, NotPaired after disconnecting).
 */
class MonitorService : Service() {
    private val scope = MainScope()
    private var running = false

    /** False while the off-body sensor says the watch is not worn; the hold follows it. */
    private val worn = MutableStateFlow(true)
    private val offBody = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val on = event.values[0] != 0f
            if (on == worn.value) return
            worn.value = on
            Log.i(TAG, if (on) "On wrist: reconnecting" else "Off wrist: pausing the connection")
            if (on) Bridge.acquire(Holder.SERVICE) else Bridge.release(Holder.SERVICE)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        Bridge.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        val prefs = Bridge.prefs
        if (!prefs.monitoring || !prefs.isPaired) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Through OpenActivity, not MainActivity: see there why the touch target must not be always-on.
        val open = PendingIntent.getActivity(this, 0, Intent(this, OpenActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        var shown = statusText(Bridge.conn.value, Bridge.sessions.value, Bridge.requests.value)
        val builder = NotificationCompat.Builder(this, Notifier.CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.monitor_title))
            .setContentText(shown)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
        val ongoing = OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_notification)
            .setTouchIntent(open)
            .setStatus(status(shown))
            .build()
        ongoing.apply(applicationContext)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: restarted by the system while the app is in
            // the background. The next app start brings it back.
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        Bridge.acquire(Holder.SERVICE)
        // An on-change sensor delivers the current state on registration, so a watch already on
        // the charger releases the hold right away.
        val sensors = getSystemService(SensorManager::class.java)
        sensors.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)?.let {
            sensors.registerListener(offBody, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        scope.launch {
            combine(Bridge.conn, Bridge.sessions, Bridge.requests, worn) { conn, sessions, requests, worn ->
                if (worn) statusText(conn, sessions, requests) else getString(R.string.ongoing_off_body)
            }
                .distinctUntilChanged()
                .conflate()
                .collect { text ->
                    if (text == shown) return@collect
                    shown = text
                    builder.setContentText(text)
                    // Without the permission the notification is hidden anyway.
                    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                        ongoing.update(applicationContext, status(text))
                    }
                    // At most one update per interval; conflate() keeps only the newest text meanwhile.
                    delay(STATUS_INTERVAL_MS)
                }
        }
        scope.launch {
            Bridge.conn.first { it is Conn.Unauthorized || it is Conn.NotPaired }
            prefs.monitoring = false
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        getSystemService(SensorManager::class.java).unregisterListener(offBody)
        if (running && worn.value) Bridge.release(Holder.SERVICE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun statusText(conn: Conn, sessions: List<Session>, requests: List<PendingRequest>): String =
        when (conn) {
            Conn.Online -> ongoingStatusText(
                sessions,
                requests,
                running = { getString(R.string.ongoing_running, it) },
                waiting = { getString(R.string.ongoing_waiting, it) },
                idle = getString(R.string.ongoing_idle),
            )
            is Conn.Incompatible -> getString(R.string.conn_incompatible)
            else -> getString(R.string.conn_connecting)
        }

    private fun status(text: String): Status = Status.Builder().addTemplate(text).build()

    companion object {
        private const val TAG = "Wristline"
        private const val NOTIFICATION_ID = 1
        private const val STATUS_INTERVAL_MS = 2_000L

        /**
         * Runs the service while monitoring is on, the watch is paired and notifications are
         * allowed (without them it could not reach the user). Call only while the app is in the
         * foreground, where starting a service is allowed.
         */
        fun sync(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            val prefs = Bridge.prefs
            if (prefs.monitoring && prefs.isPaired && Notifier.enabled(context)) context.startService(intent) else context.stopService(intent)
        }
    }
}
