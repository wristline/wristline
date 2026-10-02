package dev.wristline.watch

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.IBinder
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TtsSpan
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import dev.wristline.watch.data.Bridge
import dev.wristline.watch.data.Conn
import dev.wristline.watch.data.Holder
import dev.wristline.watch.data.PendingRequest
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.SessionStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Running sessions, and sessions waiting for the user: needs_input or with an open request, each once. */
internal data class OngoingCounts(val running: Int, val waiting: Int)

internal fun ongoingCounts(sessions: List<Session>, requests: List<PendingRequest>): OngoingCounts = OngoingCounts(
    running = sessions.count { it.status == SessionStatus.RUNNING },
    waiting = (sessions.filter { it.status == SessionStatus.NEEDS_INPUT }.map { it.id } + requests.map { it.sessionId }).toSet().size,
)

/**
 * The monitoring status when nothing waits: a glyph alone. The Now Bar chip keeps its full width
 * with no text at all too (empty or left out), showing only a blank after the icon.
 */
internal const val ONGOING_IDLE = "◦"

/**
 * Text of the monitoring ongoing activity, an icon and a number in every locale: `✋ 1`, the
 * sessions waiting for the user; [ONGOING_IDLE] when none does. Running sessions are left out (the
 * description still reads them out).
 */
internal fun ongoingStatusText(counts: OngoingCounts): String =
    if (counts.waiting > 0) "✋ ${counts.waiting}" else ONGOING_IDLE

/** The number on the Now Bar icon's badge: [waiting] up to 9, then "9+"; null (no badge) at zero. */
internal fun ongoingBadgeText(waiting: Int): String? = when {
    waiting <= 0 -> null
    waiting <= 9 -> waiting.toString()
    else -> "9+"
}

/** The same read out, e.g. "실행 2, 대기 1" ([running] and [waiting] spell out a count); both, at zero, when both are. */
internal fun ongoingStatusDescription(counts: OngoingCounts, running: (Int) -> String, waiting: (Int) -> String): String =
    ongoingParts(counts, running, waiting).ifEmpty { listOf(running(0), waiting(0)) }.joinToString(", ")

private fun ongoingParts(counts: OngoingCounts, running: (Int) -> String, waiting: (Int) -> String): List<String> = buildList {
    if (counts.running > 0) add(running(counts.running))
    if (counts.waiting > 0) add(waiting(counts.waiting))
}

/**
 * Background monitoring: holds the bridge connection ([Bridge.acquire]) while the app is closed,
 * so requests and alerts arrive as notifications. Shown as an ongoing activity with live counts
 * (on Samsung's Now Bar, a card the notification describes itself: see [nowBarExtras]).
 *
 * While the watch is off the wrist (the off-body sensor, no permission needed) the hold is
 * released, so the socket closes and the bridge's presence for this watch goes false; the status
 * reads "Not worn" meanwhile. Putting the watch back on reconnects.
 *
 * Older bridges do not push session changes other than needs-input ones to a background client, so
 * the screen turning on (when the Now Bar can be seen) fetches the session list once
 * ([Bridge.refreshOnScreenOn]) and the counts follow it.
 *
 * Long turns also get a Live Update each ([LiveUpdates], [Notifier.liveUpdate]) while the setting
 * is on ([dev.wristline.watch.data.Prefs.liveUpdates]); newer bridges push the status and task-list
 * changes they follow.
 *
 * Runs only while the user has it on in Settings ([dev.wristline.watch.data.Prefs.monitoring]).
 * There is no boot receiver: after a reboot, or when the system stops the service, it starts
 * again the next time Wristline is opened ([sync] from MainActivity). It stops itself, and turns
 * the setting off, when the pairing is gone (Unauthorized, NotPaired after disconnecting).
 */
class MonitorService : Service() {
    private val scope = MainScope()
    private var running = false

    /** The Now Bar icon and the waiting count drawn on it, re-rendered only when the count changes. */
    private var nowBarIcon: Icon? = null
    private var nowBarIconCount = -1

    /** Bumped when a running session becomes due for a Live Update by age alone ([LiveUpdates.nextDueAt]). */
    private val liveTick = MutableStateFlow(0)
    private var liveTimer: Job? = null

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

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = Bridge.refreshOnScreenOn()
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
        val first = statusText(Bridge.conn.value, Bridge.sessions.value, Bridge.requests.value)
        val firstWaiting = badgeCount(Bridge.conn.value, Bridge.sessions.value, Bridge.requests.value, worn = true)
        var shown = first.first
        val builder = NotificationCompat.Builder(this, Notifier.CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_notification)
            // OngoingActivity (wear-ongoing 1.1.0) has no color of its own; the Now Bar takes this one.
            .setColor(Notifier.COLOR)
            .setColorized(false)
            .setContentTitle(getString(R.string.monitor_title))
            .setContentText(spoken(first))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
        val ongoing = if (hasSamsungNowBar()) {
            builder.addExtras(nowBarExtras(shown, firstWaiting))
            null
        } else {
            OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
                // White on transparent, as the library asks: other surfaces tint it.
                .setStaticIcon(R.drawable.ic_notification)
                .setTouchIntent(open)
                .setStatus(status(shown))
                .build()
                .also { it.apply(applicationContext) }
        }
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
        ContextCompat.registerReceiver(this, screenOn, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)

        scope.launch {
            combine(Bridge.conn, Bridge.sessions, Bridge.requests, worn) { conn, sessions, requests, worn ->
                val status = if (worn) statusText(conn, sessions, requests) else getString(R.string.ongoing_off_body).let { it to it }
                status to badgeCount(conn, sessions, requests, worn)
            }
                .distinctUntilChanged()
                .conflate()
                .collect { (next, waiting) ->
                    val text = next.first
                    if (text == shown) return@collect
                    shown = text
                    builder.setContentText(spoken(next))
                    // Without the permission the notification is hidden anyway.
                    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                        if (ongoing != null) {
                            ongoing.update(applicationContext, status(text))
                        } else {
                            builder.addExtras(nowBarExtras(text, waiting))
                            NotificationManagerCompat.from(this@MonitorService).notify(NOTIFICATION_ID, builder.build())
                        }
                    }
                    // At most one update per interval; conflate() keeps only the newest text meanwhile.
                    delay(STATUS_INTERVAL_MS)
                }
        }
        scope.launch {
            combine(Bridge.sessions, prefs.liveUpdatesState, liveTick) { sessions, on, _ -> if (on) sessions else emptyList() }
                .conflate()
                .collect { sessions ->
                    val now = System.currentTimeMillis()
                    for (change in liveUpdates.plan(sessions, now)) {
                        when (change) {
                            is LiveChange.Post -> {
                                val session = sessions.firstOrNull { it.id == change.update.sessionId }
                                if (!Notifier.liveUpdate(this@MonitorService, change.update, session)) liveUpdates.forget(change.update.sessionId)
                            }
                            is LiveChange.Cancel -> Notifier.cancelLiveUpdate(this@MonitorService, change.sessionId)
                        }
                    }
                    liveTimer?.cancel()
                    liveTimer = liveUpdates.nextDueAt(sessions, now)?.let { at ->
                        scope.launch {
                            delay(at - now)
                            liveTick.value++
                        }
                    }
                    // Changes meanwhile are conflated into the next round.
                    delay(LIVE_UPDATE_INTERVAL_MS)
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
        // Nothing would update or end them any more.
        for (change in liveUpdates.plan(emptyList(), System.currentTimeMillis())) {
            if (change is LiveChange.Cancel) Notifier.cancelLiveUpdate(this, change.sessionId)
        }
        getSystemService(SensorManager::class.java).unregisterListener(offBody)
        if (running) unregisterReceiver(screenOn)
        if (running && worn.value) Bridge.release(Holder.SERVICE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** The status text and how TalkBack reads it. */
    private fun statusText(conn: Conn, sessions: List<Session>, requests: List<PendingRequest>): Pair<String, String> =
        when (conn) {
            Conn.Online -> ongoingCounts(sessions, requests).let { counts ->
                ongoingStatusText(counts) to ongoingStatusDescription(
                    counts,
                    running = { getString(R.string.ongoing_running, it) },
                    waiting = { getString(R.string.ongoing_waiting, it) },
                )
            }
            is Conn.Incompatible -> getString(R.string.conn_incompatible).let { it to it }
            else -> getString(R.string.conn_connecting).let { it to it }
        }

    /** The waiting count for the Now Bar icon's badge: none while not online or not worn, as the text shows none. */
    private fun badgeCount(conn: Conn, sessions: List<Session>, requests: List<PendingRequest>, worn: Boolean): Int =
        if (worn && conn is Conn.Online) ongoingCounts(sessions, requests).waiting else 0

    /** The status text, read out by TalkBack as its description rather than glyph by glyph. */
    private fun spoken(status: Pair<String, String>): CharSequence {
        val (text, description) = status
        if (text == description) return text
        return SpannableString(text).apply {
            setSpan(TtsSpan.TextBuilder(description).build(), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun status(text: String): Status = Status.Builder().addTemplate(text).build()

    /**
     * True on One UI Watch builds with the Now Bar, recognized by the gray disc their system UI
     * draws behind every OngoingActivity icon (it has no condition: a full-bleed icon cannot cover
     * it either, since the icon is inset by a padding inside the disc).
     */
    @SuppressLint("DiscouragedApi") // A resource of another package has no R constant.
    private fun hasSamsungNowBar(): Boolean = try {
        packageManager.getResourcesForApplication(SAMSUNG_SYSUI)
            .getIdentifier("nowbar_card_view_default_ongoing_icon_bg", "drawable", SAMSUNG_SYSUI) != 0
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * A Now Bar card described by the notification itself, used instead of an OngoingActivity on
     * Samsung: Wear OS services pass `extras["customDisplayBundle"]` through and the system UI reads
     * the keys below, the way Samsung's media card is made. Such a card gets no backdrop behind its
     * icon unless it names one ("cardIconBgLeft"), so the full-color [R.drawable.ic_ongoing] shows
     * bare and fills the icon slot. The icon carries the [waiting] count as a badge
     * ([nowBarIcon]), so the card still shows it with the Now Bar set to icons only.
     */
    private fun nowBarExtras(text: String, waiting: Int): Bundle {
        val icon = nowBarIcon(waiting)
        val card = Bundle().apply {
            putInt("type", 1)
            putParcelable("cardIconLeft", icon)
            putParcelable("expandViewIcon", icon)
            putParcelable("queIcon", icon)
            putString("cardContents", text)
            putString("expandPrimaryInfo", text)
            putString("expandSecondaryInfo", getString(R.string.app_name))
        }
        val display = Bundle().apply {
            putBoolean("enableNowBar", true)
            putBundle("nowBarData", card)
        }
        return Bundle().apply { putBundle("customDisplayBundle", display) }
    }

    /**
     * [R.drawable.ic_ongoing] as a bitmap with, while [waiting] > 0, a badge at its top right: an
     * attention-yellow disc (Status.Attention) about 40% of the icon, outlined dark so it reads on
     * the white squircle, holding the count ([ongoingBadgeText]) in black bold.
     */
    private fun nowBarIcon(waiting: Int): Icon {
        nowBarIcon?.takeIf { nowBarIconCount == waiting }?.let { return it }
        val size = NOW_BAR_ICON_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        ContextCompat.getDrawable(this, R.drawable.ic_ongoing)!!.apply { setBounds(0, 0, size, size) }.draw(canvas)
        ongoingBadgeText(waiting)?.let { label ->
            val radius = size * 0.2f
            val cx = size - radius
            val cy = radius
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = BADGE_OUTLINE
            canvas.drawCircle(cx, cy, radius, paint)
            paint.color = BADGE_FILL
            canvas.drawCircle(cx, cy, radius - BADGE_OUTLINE_PX, paint)
            paint.color = BADGE_TEXT
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = radius * if (label.length == 1) 1.5f else 1.15f
            val metrics = paint.fontMetrics
            canvas.drawText(label, cx, cy - (metrics.ascent + metrics.descent) / 2, paint)
        }
        return Icon.createWithBitmap(bitmap).also {
            nowBarIcon = it
            nowBarIconCount = waiting
        }
    }

    companion object {
        private const val TAG = "Wristline"
        private const val NOTIFICATION_ID = 1
        private const val STATUS_INTERVAL_MS = 2_000L
        private const val SAMSUNG_SYSUI = "com.samsung.android.wearable.sysui"
        private const val NOW_BAR_ICON_PX = 96
        private const val BADGE_OUTLINE_PX = 2f
        private const val BADGE_FILL = 0xFFFFD60A.toInt() // Status.Attention
        private const val BADGE_OUTLINE = 0xFF000000.toInt()
        private const val BADGE_TEXT = 0xFF000000.toInt()

        /** The Live Updates this service posts; process-wide, so a swipe-away reaches it ([LiveUpdateDismissReceiver]). */
        internal val liveUpdates = LiveUpdates()

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
