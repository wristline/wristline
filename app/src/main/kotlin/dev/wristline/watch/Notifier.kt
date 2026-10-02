package dev.wristline.watch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.TtsSpan
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.wristline.watch.data.AccountBook
import dev.wristline.watch.data.Bridge
import dev.wristline.watch.data.Decision
import dev.wristline.watch.data.PERMISSION_QUESTION
import dev.wristline.watch.data.PendingRequest
import dev.wristline.watch.data.ProviderId
import dev.wristline.watch.data.RequestKind
import dev.wristline.watch.data.ServerEvent
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.SessionStatus
import dev.wristline.watch.data.Usage
import dev.wristline.watch.data.look
import dev.wristline.watch.ui.LimitEnd
import dev.wristline.watch.ui.basename
import dev.wristline.watch.ui.errorRes
import dev.wristline.watch.ui.limitEnd
import dev.wristline.watch.ui.markedProviders
import dev.wristline.watch.ui.permissionQuestion
import dev.wristline.watch.ui.progressDoneRes
import dev.wristline.watch.ui.resetClockText
import java.time.ZoneId

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
 * Request titles (the tool) whose text sums the tool's input up rather than showing all of it: a
 * file's path but not the change (Claude Code's file tools, Codex's "Edit"), a subagent's
 * description but not its prompt, a search's pattern but not where it looks.
 */
private val SUMMARIZED_TOOLS = setOf("Edit", "Write", "MultiEdit", "NotebookEdit", "Task", "Agent", "Grep", "Glob")

/**
 * The decisions a request's notification offers as actions, in order: Allow only when the
 * notification shows the whole command on one line (what is approved must be what was read), when
 * the bridge did not clip it either ("…" at the end), and when the text is the tool's whole input
 * (not one of [SUMMARIZED_TOOLS]); Deny whenever the question has it. Never Always: a lasting rule
 * wants the full screen. None for questions, which need choices.
 */
internal fun notificationDecisions(request: PendingRequest): List<String> {
    if (request.kind != RequestKind.PERMISSION) return emptyList()
    val question = request.permissionQuestion() ?: return emptyList()
    if (question.multi) return emptyList()
    val ids = question.options.map { it.id }
    val text = question.text
    val whole = request.title !in SUMMARIZED_TOOLS && text.isNotBlank() && text.length <= NOTIFY_TEXT_MAX &&
        !text.endsWith('…') && text.lines().size == 1
    return listOfNotNull(Decision.ALLOW.takeIf { whole && it in ids }, Decision.DENY.takeIf { it in ids })
}

/**
 * `Codex · Work`: [session]'s provider ([providerName]) and its account's name in [book] (the
 * nickname, else the label), when the provider has more than one account ([markedProviders] of
 * [usage] and [sessions]), as its cards' badges are marked; else null.
 */
internal fun accountLine(providerName: String, session: Session?, usage: List<Usage>, sessions: List<Session>, book: AccountBook): String? {
    val account = session?.account ?: return null
    if (session.provider !in markedProviders(usage, sessions)) return null
    return providerName + " · " + book.look(session.provider, account).name
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
    const val CHANNEL_LIVE = "live"

    /** The silent channel done alerts used before [CHANNEL_UPDATES]. */
    private const val OLD_CHANNEL_UPDATES = "updates"

    private const val TAG_REQUEST = "request"
    private const val TAG_SESSION = "session"
    /** A usage-limit alert: keyed by session id like [TAG_SESSION], apart from its needs-input and done alerts. */
    private const val TAG_LIMIT = "limit"
    /** A long turn's Live Update ([liveUpdate]): keyed by session id, apart from the session's alerts. */
    private const val TAG_LIVE = "live"

    /** The app's primary (the theme's), for the small icon here and in the Now Bar: gray without it. */
    val COLOR: Int = 0xFF4FA8FF.toInt()
    /**
     * The Live Updates' colour, the icon's navy: the system draws a backdrop circle of it behind
     * their small icon in the Now Bar. Samsung keeps its hue but normalises its lightness, and
     * forces a neutral grey to a fixed grey whatever its shade, so only the hue is ours.
     */
    private val LIVE_COLOR: Int = 0xFF16324F.toInt()
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
        // Live Updates: silent, as they change often; not IMPORTANCE_MIN, which may not be promoted.
        val live = NotificationChannel(
            CHANNEL_LIVE,
            context.getString(R.string.channel_live),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(OLD_CHANNEL_UPDATES)
        manager.createNotificationChannels(listOf(requests, updates, monitor, live))
    }

    fun enabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** True when posted (false without the notification permission); likewise below. */
    fun request(context: Context, request: PendingRequest, session: Session?): Boolean =
        request(context, request, sessionTitle(context, session), account = accountLine(context, session))

    /**
     * The request's question (clipped to [NOTIFY_TEXT_MAX]) under its title, the session as the
     * sub text after the [account] line ([accountLine]), and, when [answerable], the [notificationDecisions] as actions
     * ([NotificationActionReceiver]). [note] is a line above the question about an action's answer
     * (sending, not sent, already handled); a notification with one is posted silently. With
     * [timeoutMs] it goes away by itself.
     */
    internal fun request(
        context: Context,
        request: PendingRequest,
        sessionTitle: String,
        note: String? = null,
        answerable: Boolean = true,
        timeoutMs: Long? = null,
        account: String? = null,
    ): Boolean {
        val question = request.permissionQuestion()?.text.orEmpty()
        val body = clipText(question, NOTIFY_TEXT_MAX).ifBlank { sessionTitle }
        return post(
            context, TAG_REQUEST, request.id, CHANNEL_REQUESTS, MainActivity.EXTRA_REQUEST_ID,
            // Opens the request over its session, so Back goes to the session first.
            sessionId = request.sessionId,
            title = request.title.ifBlank { context.getString(R.string.status_needs_input) },
            text = listOfNotNull(note, body).joinToString("\n"),
            subText = listOfNotNull(account, sessionTitle.takeIf { question.isNotBlank() }).joinToString(" · ").ifEmpty { null },
            silent = note != null,
            timeoutMs = timeoutMs,
            actions = if (answerable) notificationDecisions(request).map { decisionAction(context, request, it, sessionTitle, account) } else emptyList(),
        )
    }

    /** The [request] note for an answer the bridge refused with [code], or did not get ("unreachable"). */
    fun answerFailed(context: Context, code: String): String {
        val message = errorRes(code)?.let(context::getString) ?: context.getString(R.string.error_generic, code)
        return context.getString(R.string.notify_answer_failed, message)
    }

    /**
     * Answers without opening the app. Authentication: a locked watch asks to unlock first. No UI:
     * the receiver answers in the background, and Wear OS is told the tap opens nothing.
     */
    private fun decisionAction(context: Context, request: PendingRequest, decision: String, sessionTitle: String, account: String?): NotificationCompat.Action {
        val question = request.permissionQuestion()?.id ?: PERMISSION_QUESTION
        val intent = NotificationActionReceiver.intent(context, request, question, decision, sessionTitle, account)
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
            subText = accountLine(context, session),
            // Replacing an earlier alert of the session (e.g. its "Finished") must vibrate again.
            alertOnce = false,
        )

    /** [title] is the alert's own title, when the bridge sends one; otherwise the session title. */
    fun done(context: Context, sessionId: String, title: String?, text: String?, session: Session?): Boolean =
        post(
            context, TAG_SESSION, sessionId, CHANNEL_UPDATES, MainActivity.EXTRA_SESSION_ID,
            title = title?.takeIf { it.isNotBlank() } ?: sessionTitle(context, session),
            text = text ?: context.getString(R.string.notify_done),
            subText = accountLine(context, session),
            // Each done alert is new (replays are deduplicated by id). A background client is not told
            // that the session ran again, so the previous one may still be shown: vibrate anyway.
            alertOnce = false,
        )

    /**
     * The agent hit a usage limit: titled in English as the agent says it, the session and when the
     * limit ends ([limitText], [limitEndText]) below, the agent's own message when expanded; which
     * account, when its provider has more than one, as the sub text ([accountLine]). [usage] tells
     * when it ends when the alert does not ([limitEnd]).
     */
    fun limit(context: Context, alert: ServerEvent.Alert, session: Session?, usage: List<Usage>): Boolean {
        val now = System.currentTimeMillis()
        val end = limitEnd(alert.limitKind, alert.resetsAt, alert.resetsEstimated, session, usage, now)
        val endText = end?.let { limitEndText(it, now, ZoneId.systemDefault(), DateFormat.is24HourFormat(context)) }
        val line = limitText(sessionTitle(context, session), endText)
        return post(
            context, TAG_LIMIT, alert.sessionId, CHANNEL_REQUESTS, MainActivity.EXTRA_SESSION_ID,
            title = context.getString(R.string.notify_limit),
            text = line,
            bigText = alert.text?.takeIf { it.isNotBlank() }?.let { "$line\n$it" },
            alertOnce = false,
            subText = accountLine(context, session),
        )
    }

    /** `Fix CI · ◷ 07:40 PM`: the session, then when the limit ends ([limitEndText]) when known. */
    internal fun limitText(sessionTitle: String, end: String?): String =
        if (end == null) sessionTitle else "$sessionTitle · $end"

    /** When a limit ends, in English as its card shows it: `◷ 07:40 PM`, `◷ ~07:40 PM` (estimated) or `¤ credits` ([resetClockText]). */
    internal fun limitEndText(end: LimitEnd, now: Long, zone: ZoneId, is24Hour: Boolean): String = when (end) {
        is LimitEnd.At -> "◷ " + (if (end.estimated) "~" else "") + resetClockText(end.millis, now, zone, is24Hour)
        LimitEnd.Credits -> "¤ credits"
    }

    /** Live Updates exist from Wear OS 7 (API 37) on; elsewhere none is posted and the setting is left out. */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.CINNAMON_BUN)
    fun liveUpdatesSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN

    /**
     * The system will promote [liveUpdate]'s notifications: [liveUpdatesSupported], and the user has
     * not turned the app's Live Updates off in the system settings (the permission,
     * POST_PROMOTED_NOTIFICATIONS, is granted at install).
     */
    fun canPostLiveUpdates(context: Context): Boolean =
        liveUpdatesSupported() && NotificationManagerCompat.from(context).canPostPromotedNotifications()

    /** The Live Updates' small icon ([OngoingIcon], no badge), drawn once. */
    private var liveIcon: Bitmap? = null

    /**
     * A long turn's Live Update, promoted to the watch face's status chip: the chip text
     * ([liveUpdateChip]) and the title ([liveUpdateTitle]: the session's, then the task in
     * progress), the two lines the Now Bar's expanded card shows; the status ([liveUpdateText]) and
     * account line as the text, and a [NotificationCompat.ProgressStyle] bar: one segment per task
     * of its task list, else indeterminate with the time since the turn started as a chronometer
     * (both for the notification shade). The small icon is the Now Bar card's own ([OngoingIcon]):
     * the Now Bar draws it untinted on a disc of [LIVE_COLOR]. Swiping it away tells
     * [LiveUpdateDismissReceiver]. Posts nothing where it would not be promoted ([canPostLiveUpdates]).
     */
    internal fun liveUpdate(context: Context, update: LiveUpdate, session: Session?): Boolean {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!canPostLiveUpdates(context)) return false
        val id = notificationId(update.sessionId)
        val open = MainActivity.openIntent(context).putExtra(MainActivity.EXTRA_SESSION_ID, update.sessionId)
        val openIntent = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val dismiss = LiveUpdateDismissReceiver.intent(context, update.sessionId, update.turn)
        val dismissIntent = PendingIntent.getBroadcast(context, 0, dismiss, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val progress = liveProgress(update)
        // Not apply {}: Style has a member apply(builder), which would be called instead.
        val style = NotificationCompat.ProgressStyle()
        if (progress == null) {
            style.setProgressIndeterminate(true)
        } else {
            style.setProgressSegments(List(progress.total) { NotificationCompat.ProgressStyle.Segment(1).setColor(COLOR) })
                .setProgress(progress.done)
        }
        val status = liveUpdateText(update)
        val text = listOfNotNull(status, accountLine(context, session)).joinToString(" · ")
        val spoken = when {
            update.waiting -> context.getString(R.string.status_needs_input)
            else -> listOfNotNull(
                progress?.let { context.resources.getQuantityString(progressDoneRes(it), it.total, it.done, it.total) }
                    ?: context.getString(R.string.status_running),
                progress?.let { liveCurrent(update) },
                update.minutes?.let { context.resources.getQuantityString(R.plurals.duration_minutes, it, it) },
            ).joinToString(", ")
        }
        val icon = liveIcon ?: OngoingIcon.bitmap(context).also { liveIcon = it }
        val notification = NotificationCompat.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(IconCompat.createWithBitmap(icon))
            .setColor(LIVE_COLOR)
            // A colorized notification is never promoted.
            .setColorized(false)
            .setContentTitle(liveUpdateTitle(sessionTitle(context, session), update))
            .setContentText(SpannableString(text).apply { setSpan(TtsSpan.TextBuilder(spoken).build(), 0, status.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) })
            .setStyle(style)
            .setShortCriticalText(liveUpdateChip(update))
            .apply {
                if (progress == null && update.startedAt != null) {
                    setWhen(update.startedAt)
                    setShowWhen(true)
                    setUsesChronometer(true)
                }
            }
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openIntent)
            .setDeleteIntent(dismissIntent)
            .setOngoing(true)
            .setRequestPromotedOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        NotificationManagerCompat.from(context).notify(TAG_LIVE, id, notification)
        return true
    }

    fun cancelLiveUpdate(context: Context, sessionId: String) {
        NotificationManagerCompat.from(context).cancel(TAG_LIVE, notificationId(sessionId))
    }

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
            if ((active.tag == TAG_SESSION || active.tag == TAG_LIMIT) && active.id == id &&
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
     * waiting; a done or limit alert of one that is not running (an ended one is no longer listed);
     * a Live Update of one still running or waiting. Untagged ones (monitoring) always do.
     */
    internal fun notificationApplies(tag: String?, channel: String?, id: Int, requests: List<PendingRequest>, sessions: List<Session>): Boolean =
        when (tag) {
            TAG_REQUEST -> requests.any { notificationId(it.id) == id }
            TAG_LIMIT -> sessions.any { notificationId(it.id) == id && it.status != SessionStatus.RUNNING }
            TAG_LIVE -> sessions.any { notificationId(it.id) == id && (it.status == SessionStatus.RUNNING || it.status == SessionStatus.NEEDS_INPUT) }
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
        bigText: String? = null,
        alertOnce: Boolean = true,
        subText: String? = null,
        silent: Boolean = false,
        timeoutMs: Long? = null,
        actions: List<NotificationCompat.Action> = emptyList(),
        sessionId: String? = null,
    ): Boolean {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val id = notificationId(key)
        val open = MainActivity.openIntent(context).putExtra(extra, key)
            .apply { sessionId?.let { putExtra(MainActivity.EXTRA_SESSION_ID, it) } }
        // The request code keeps the PendingIntents of different notifications apart (extras do not count).
        val openIntent = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(COLOR)
            .setColorized(false)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(subText)
            // Done alerts carry up to about 500 characters; expanded on the watch they show in full.
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText ?: text))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(alertOnce)
            .setSilent(silent)
            .apply { timeoutMs?.let(::setTimeoutAfter) }
            .apply { actions.forEach(::addAction) }
            .build()
        NotificationManagerCompat.from(context).notify(tag, id, notification)
        return true
    }

    /** [accountLine] for [session] as the Bridge has it now. */
    private fun accountLine(context: Context, session: Session?): String? {
        val provider = session?.provider ?: return null
        val name = when (provider) {
            ProviderId.CLAUDE_CODE -> context.getString(R.string.provider_claude_code)
            ProviderId.CODEX -> context.getString(R.string.provider_codex)
            else -> provider
        }
        return accountLine(name, session, Bridge.usage.value, Bridge.sessions.value, Bridge.prefs.accounts.value)
    }

    /** Same fallbacks as the screens' session title. */
    private fun sessionTitle(context: Context, session: Session?): String =
        session?.title.orEmpty().ifBlank { basename(session?.cwd.orEmpty()) }
            .ifBlank { context.getString(R.string.session_untitled) }
}
