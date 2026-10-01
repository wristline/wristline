package dev.wristline.watch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.wristline.watch.data.Decision
import dev.wristline.watch.data.PERMISSION_QUESTION
import dev.wristline.watch.data.PendingRequest
import dev.wristline.watch.data.RequestKind
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.SessionStatus
import dev.wristline.watch.ui.basename
import dev.wristline.watch.ui.errorRes
import dev.wristline.watch.ui.permissionQuestion

/**
 * Notification id for a request or session id. String.hashCode is specified by the Java API, so
 * the id is the same in every process: a notification posted before the process died can still be
 * cancelled after a restart.
 */
internal fun notificationId(key: String): Int = key.hashCode()

/** Characters of a request's question its notification shows. */
internal const val NOTIFY_TEXT_MAX = 400

/** [text] cut to at most [max] characters, ending in "…" when cut, never between a surrogate pair. */
internal fun clipText(text: String, max: Int): String {
    if (text.length <= max) return text
    var end = max - 1
    if (Character.isHighSurrogate(text[end - 1])) end--
    return text.substring(0, end) + "…"
}

/**
 * The decisions a request's notification offers as actions, in order: Allow only when the
 * notification shows the whole command on one line (what is approved must be what was read), and
 * when the bridge did not clip it either ("…" at the end); Deny whenever the question has it.
 * Never Always: a lasting rule wants the full screen. None for questions, which need choices.
 */
internal fun notificationDecisions(request: PendingRequest): List<String> {
    if (request.kind != RequestKind.PERMISSION) return emptyList()
    val question = request.permissionQuestion() ?: return emptyList()
    if (question.multi) return emptyList()
    val ids = question.options.map { it.id }
    val text = question.text
    val whole = text.isNotBlank() && text.length <= NOTIFY_TEXT_MAX && !text.endsWith('…') && text.lines().size == 1
    return listOfNotNull(Decision.ALLOW.takeIf { whole && it in ids }, Decision.DENY.takeIf { it in ids })
}

/**
 * Notifications for requests and alerts; while the user looks at the app a haptic plays instead
 * ([dev.wristline.watch.data.Haptics]). Request notifications are tagged [TAG_REQUEST] and keyed
 * by request id; alert notifications are tagged [TAG_SESSION] and keyed by session id, so a newer
 * alert for a session replaces the older one. The tags keep both apart from the monitoring
 * notification (no tag).
 */
object Notifier {
    const val CHANNEL_REQUESTS = "requests"
    /** A new id: an existing channel's importance and vibration cannot be changed by the app. */
    const val CHANNEL_UPDATES = "updates_v2"
    const val CHANNEL_MONITOR = "monitor"

    /** The silent channel done alerts used before [CHANNEL_UPDATES]. */
    private const val OLD_CHANNEL_UPDATES = "updates"

    private const val TAG_REQUEST = "request"
    private const val TAG_SESSION = "session"
    private val VIBRATION = longArrayOf(0, 250, 150, 250)
    private val SHORT_VIBRATION = longArrayOf(0, 200)

    /** Idempotent; also refreshes the channel names after a language change. */
    fun createChannels(context: Context) {
        val requests = NotificationChannel(
            CHANNEL_REQUESTS,
            context.getString(R.string.channel_requests),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            enableVibration(true)
            vibrationPattern = VIBRATION
        }
        val updates = NotificationChannel(
            CHANNEL_UPDATES,
            context.getString(R.string.channel_updates),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            enableVibration(true)
            vibrationPattern = SHORT_VIBRATION
        }
        // The foreground-service notification: silent, and hideable without muting the other two.
        val monitor = NotificationChannel(
            CHANNEL_MONITOR,
            context.getString(R.string.channel_monitor),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(OLD_CHANNEL_UPDATES)
        manager.createNotificationChannels(listOf(requests, updates, monitor))
    }

    fun enabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** True when posted (false without the notification permission); likewise below. */
    fun request(context: Context, request: PendingRequest, session: Session?): Boolean =
        request(context, request, sessionTitle(context, session))

    /**
     * The request's question (clipped to [NOTIFY_TEXT_MAX]) under its title, the session as the
     * sub text, and the [notificationDecisions] as actions ([NotificationActionReceiver]). [error]
     * is an action's error code: posted again, the notification says it was not sent. Re-posting
     * does not alert again (alert once).
     */
    internal fun request(context: Context, request: PendingRequest, sessionTitle: String, error: String? = null): Boolean {
        val question = request.permissionQuestion()?.text.orEmpty()
        val body = clipText(question, NOTIFY_TEXT_MAX).ifBlank { sessionTitle }
        val failed = error?.let {
            val message = errorRes(it)?.let(context::getString) ?: context.getString(R.string.error_generic, it)
            context.getString(R.string.notify_answer_failed, message)
        }
        return post(
            context, TAG_REQUEST, request.id, CHANNEL_REQUESTS, MainActivity.EXTRA_REQUEST_ID,
            title = request.title.ifBlank { context.getString(R.string.status_needs_input) },
            text = listOfNotNull(failed, body).joinToString("\n"),
            subText = sessionTitle.takeIf { question.isNotBlank() },
            actions = notificationDecisions(request).map { decisionAction(context, request, it, sessionTitle) },
        )
    }

    /**
     * Answers without opening the app. Authentication: a locked watch asks to unlock first. No UI:
     * the receiver answers in the background, and Wear OS is told the tap opens nothing.
     */
    private fun decisionAction(context: Context, request: PendingRequest, decision: String, sessionTitle: String): NotificationCompat.Action {
        val question = request.permissionQuestion()?.id ?: PERMISSION_QUESTION
        val intent = NotificationActionReceiver.intent(context, request, question, decision, sessionTitle)
        // The intent's data is unique per request and decision, so the PendingIntents stay apart.
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val allow = decision == Decision.ALLOW
        return NotificationCompat.Action.Builder(
            if (allow) R.drawable.ic_check else R.drawable.ic_block,
            context.getString(if (allow) R.string.decision_allow else R.string.decision_deny),
            pending,
        )
            .setAuthenticationRequired(true)
            .setShowsUserInterface(false)
            .extend(NotificationCompat.Action.WearableExtender().setHintLaunchesActivity(false))
            .build()
    }

    /** The agent waits for input that is not a PendingRequest (e.g. a dialog only the terminal shows). */
    fun needsInput(context: Context, sessionId: String, text: String?, session: Session?): Boolean =
        post(
            context, TAG_SESSION, sessionId, CHANNEL_REQUESTS, MainActivity.EXTRA_SESSION_ID,
            title = sessionTitle(context, session),
            text = text ?: context.getString(R.string.status_needs_input),
            // Replacing an earlier alert of the session (e.g. its "Finished") must vibrate again.
            alertOnce = false,
        )

    /** [title] is the alert's own title, when the bridge sends one; otherwise the session title. */
    fun done(context: Context, sessionId: String, title: String?, text: String?, session: Session?): Boolean =
        post(
            context, TAG_SESSION, sessionId, CHANNEL_UPDATES, MainActivity.EXTRA_SESSION_ID,
            title = title?.takeIf { it.isNotBlank() } ?: sessionTitle(context, session),
            text = text ?: context.getString(R.string.notify_done),
            // Each done alert is new (replays are deduplicated by id). A background client is not told
            // that the session ran again, so the previous one may still be shown: vibrate anyway.
            alertOnce = false,
        )

    fun cancelRequest(context: Context, requestId: String) {
        NotificationManagerCompat.from(context).cancel(TAG_REQUEST, notificationId(requestId))
    }

    fun cancelSession(context: Context, sessionId: String) {
        NotificationManagerCompat.from(context).cancel(TAG_SESSION, notificationId(sessionId))
    }

    /** Cancels [session]'s alert when its status no longer fits it (see [notificationApplies]). */
    fun cancelStale(context: Context, session: Session) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val id = notificationId(session.id)
        for (active in manager.activeNotifications) {
            if (active.tag == TAG_SESSION && active.id == id &&
                !notificationApplies(active.tag, active.notification.channelId, id, emptyList(), listOf(session))
            ) {
                manager.cancel(active.tag, active.id)
            }
        }
    }

    /**
     * Cancels the notifications that no longer apply (see [notificationApplies]). Also clears
     * notifications left by an earlier process. Empty lists cancel everything except the
     * monitoring notification.
     */
    fun reconcile(context: Context, requests: List<PendingRequest>, sessions: List<Session>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        for (active in manager.activeNotifications) {
            if (!notificationApplies(active.tag, active.notification.channelId, active.id, requests, sessions)) {
                manager.cancel(active.tag, active.id)
            }
        }
    }

    /**
     * Whether the notification [tag]/[id] posted on [channel] still applies: a request in
     * [requests]; a needs-input alert (requests channel) of a session in [sessions] that is still
     * waiting; a done alert of one that is not running (an ended one is no longer listed). Untagged
     * ones (monitoring) always do.
     */
    internal fun notificationApplies(tag: String?, channel: String?, id: Int, requests: List<PendingRequest>, sessions: List<Session>): Boolean =
        when (tag) {
            TAG_REQUEST -> requests.any { notificationId(it.id) == id }
            TAG_SESSION -> sessions.any { session ->
                notificationId(session.id) == id && when (channel) {
                    CHANNEL_REQUESTS -> session.status == SessionStatus.NEEDS_INPUT
                    else -> session.status != SessionStatus.RUNNING
                }
            }
            else -> true
        }

    private fun post(
        context: Context,
        tag: String,
        key: String,
        channel: String,
        extra: String,
        title: String,
        text: String,
        alertOnce: Boolean = true,
        subText: String? = null,
        actions: List<NotificationCompat.Action> = emptyList(),
    ): Boolean {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val id = notificationId(key)
        val open = MainActivity.openIntent(context).putExtra(extra, key)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(subText)
            // Done alerts carry up to about 500 characters; expanded on the watch they show in full.
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            // The request code keeps the PendingIntents of different notifications apart (extras do not count).
            .setContentIntent(
                PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            .setAutoCancel(true)
            .setOnlyAlertOnce(alertOnce)
            .apply { actions.forEach(::addAction) }
            .build()
        NotificationManagerCompat.from(context).notify(tag, id, notification)
        return true
    }

    /** Same fallbacks as the screens' session title. */
    private fun sessionTitle(context: Context, session: Session?): String =
        session?.title.orEmpty().ifBlank { basename(session?.cwd.orEmpty()) }
            .ifBlank { context.getString(R.string.session_untitled) }
}
