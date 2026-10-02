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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/** Running sessions, and sessions waiting for the user: needs_input or with an open request, each once. */
internal data class OngoingCounts(val running: Int, val waiting: Int)

internal fun ongoingCounts(sessions: List<Session>, requests: List<PendingRequest>): OngoingCounts = OngoingCounts(
    running = sessions.count { it.status == SessionStatus.RUNNING },
    waiting = (sessions.filter { it.status == SessionStatus.NEEDS_INPUT }.map { it.id } + requests.map { it.sessionId }).toSet().size,
)

/**
 * Text of the monitoring ongoing activity, glyphs and numbers in every locale: `▶ 2 · ✋ 1`, the
 * sessions running and those waiting for the user, both always (zeros too), so the Now Bar chip
 * keeps one width and layout.
 */
internal fun ongoingStatusText(counts: OngoingCounts): String = "▶ ${counts.running} · ✋ ${counts.waiting}"

/** The same read out, e.g. "실행 2, 대기 1" ([running] and [waiting] spell out a count); both, at zero, when both are. */
internal fun ongoingStatusDescription(counts: OngoingCounts, running: (Int) -> String, waiting: (Int) -> String): String =
    ongoingParts(counts, running, waiting).ifEmpty { listOf(running(0), waiting(0)) }.joinToString(", ")

/**
 * What the monitoring card shows: its [text], and its icon ([OngoingIcon]): the [waiting] and
 * [running] counts as badges, greyed out when [offline]. The notification is re-posted, and the
 * icon re-drawn, only when this changes, as every post moves the card to the top of the Now Bar,
 * above the Live Updates: never for a connection blip ([settledConn]).
 */
internal data class MonitorContent(val text: String, val waiting: Int = 0, val running: Int = 0, val offline: Boolean = false)

/**
 * [MonitorContent] for the state: the counts while worn and online; [notWorn] or [notOnline]'s
 * text for the connection ([shownConn]) else, with a grey icon and no counts.
 */
internal fun monitorContent(conn: Conn, counts: OngoingCounts, worn: Boolean, notWorn: String, notOnline: (Conn) -> String): MonitorContent =
    when {
        !worn -> MonitorContent(notWorn, offline = true)
        conn is Conn.Online -> MonitorContent(ongoingStatusText(counts), counts.waiting, counts.running)
        else -> MonitorContent(notOnline(conn), offline = true)
    }

/** The connection as the monitoring card tells it: Offline and Unreachable read "Connecting" too, so they are one state. */
internal fun shownConn(conn: Conn): Conn = if (conn is Conn.Offline || conn is Conn.Unreachable) Conn.Connecting else conn

/** A drop the monitoring card does not show unless it lasts this long ([settledConn]). */
internal const val CONN_GRACE_MS = 30_000L

/**
 * [conn] as [shownConn], a state other than Online or Incompatible only once it has held for
 * [CONN_GRACE_MS]: a reconnect within it leaves the card (and its place on the Now Bar) as it was.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun settledConn(conn: Flow<Conn>): Flow<Conn> = conn.map(::shownConn).distinctUntilChanged().transformLatest {
    if (it !is Conn.Online && it !is Conn.Incompatible) delay(CONN_GRACE_MS)
    emit(it)
}

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

    /** The Now Bar icon ([OngoingIcon]) and the content it was drawn for, re-rendered only when its counts or greying change. */
    private var nowBarIcon: Icon? = null
    private var nowBarIconFor: MonitorContent? = null

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
        val firstCounts = ongoingCounts(Bridge.sessions.value, Bridge.requests.value)
        val firstConn = shownConn(Bridge.conn.value)
        var shown = content(firstConn, firstCounts, worn = true)
        val builder = NotificationCompat.Builder(this, Notifier.CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_notification)
            // OngoingActivity (wear-ongoing 1.1.0) has no color of its own; the Now Bar takes this one.
            .setColor(Notifier.COLOR)
            .setColorized(false)
            .setContentTitle(getString(R.string.monitor_title))
            .setContentText(spoken(shown.text, description(firstConn, firstCounts, worn = true)))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
        val ongoing = if (hasSamsungNowBar()) {
            builder.addExtras(nowBarExtras(shown))
            null
        } else {
            OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
                // White on transparent, as the library asks: other surfaces tint it.
                .setStaticIcon(R.drawable.ic_notification)
                .setTouchIntent(open)
                .setStatus(status(shown.text))
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
            combine(settledConn(Bridge.conn), Bridge.sessions, Bridge.requests, worn) { conn, sessions, requests, worn ->
                val counts = ongoingCounts(sessions, requests)
                content(conn, counts, worn) to description(conn, counts, worn)
            }
                // The description follows the counts in the text, so a change to it alone posts nothing.
                .distinctUntilChangedBy { it.first }
                .conflate()
                .collect { (next, description) ->
                    // Back to what is shown within an interval (conflated): nothing to post.
                    if (next == shown) return@collect
                    shown = next
                    builder.setContentText(spoken(next.text, description))
                    // Without the permission the notification is hidden anyway.
                    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                        if (ongoing != null) {
                            ongoing.update(applicationContext, status(next.text))
                        } else {
                            builder.addExtras(nowBarExtras(next))
                            NotificationManagerCompat.from(this@MonitorService).notify(NOTIFICATION_ID, builder.build())
                            // Now on top of them: post the Live Updates again, once, to keep them above it.
                            for (update in liveUpdates.reposts()) {
                                val session = Bridge.sessions.value.firstOrNull { it.id == update.sessionId }
                                if (!Notifier.liveUpdate(this@MonitorService, update, session)) liveUpdates.forget(update.sessionId)
                            }
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

    /** What the card shows ([monitorContent]). */
    private fun content(conn: Conn, counts: OngoingCounts, worn: Boolean): MonitorContent =
        monitorContent(conn, counts, worn, getString(R.string.ongoing_off_body)) {
            getString(if (it is Conn.Incompatible) R.string.conn_incompatible else R.string.conn_connecting)
        }

    /** How TalkBack reads the card's text: the running and waiting counts while it shows them, else the text itself. */
    private fun description(conn: Conn, counts: OngoingCounts, worn: Boolean): String? =
        if (worn && conn is Conn.Online) {
            ongoingStatusDescription(
                counts,
                running = { getString(R.string.ongoing_running, it) },
                waiting = { getString(R.string.ongoing_waiting, it) },
            )
        } else {
            null
        }

    /** The status text, read out by TalkBack as its [description] rather than glyph by glyph. */
    private fun spoken(text: String, description: String?): CharSequence {
        if (description == null || text == description) return text
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
     * bare and fills the icon slot. The icon carries the counts as badges and greys out offline
     * ([nowBarIcon]), so the card still tells the state with the Now Bar set to icons only.
     */
    private fun nowBarExtras(content: MonitorContent): Bundle {
        val text = content.text
        val icon = nowBarIcon(content)
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

    /** [OngoingIcon] for [content]'s counts and greying. */
    private fun nowBarIcon(content: MonitorContent): Icon {
        val key = content.copy(text = "")
        nowBarIcon?.takeIf { nowBarIconFor == key }?.let { return it }
        return Icon.createWithBitmap(OngoingIcon.bitmap(this, content.waiting, running = content.running, offline = content.offline)).also {
            nowBarIcon = it
            nowBarIconFor = key
        }
    }

    companion object {
        private const val TAG = "Wristline"
        private const val NOTIFICATION_ID = 1
        private const val STATUS_INTERVAL_MS = 2_000L
        private const val SAMSUNG_SYSUI = "com.samsung.android.wearable.sysui"

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
