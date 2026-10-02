package dev.wristline.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dev.wristline.watch.data.Bridge
import dev.wristline.watch.data.Conn
import dev.wristline.watch.data.Decision
import dev.wristline.watch.data.Haptic
import dev.wristline.watch.data.Haptics
import dev.wristline.watch.data.PendingRequest
import dev.wristline.watch.data.Sent
import dev.wristline.watch.data.WireJson
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The whole answer must fit in the 10 s a foreground broadcast gets (goAsync() does not extend
 * it); the client's own timeouts (10 s connect, 20 s read) would not.
 */
private const val ANSWER_TIMEOUT_MS = 8_000L

/** How long a notice about an answer stays ([AfterAnswer.Notice]). */
private const val NOTICE_MS = 10_000L

/** Note code for an answer whose fate is not known: it may have reached the bridge after all. */
internal const val UNCONFIRMED = "unconfirmed"

/** What the notification shows once an action's answer is through. */
internal sealed interface AfterAnswer {
    /** Sent: the bridge client cancelled it. */
    data object Gone : AfterAnswer

    /** The request is settled: a short notice without actions says [code] (no retry would work). */
    data class Notice(val code: String) : AfterAnswer

    /** Posted again with [code] and the actions, so the user can try again or open the app. */
    data class Retry(val code: String) : AfterAnswer
}

/** What follows an action's answer: [haptic], and what the notification shows [after]. */
internal data class ActionOutcome(val haptic: Haptic, val after: AfterAnswer)

/**
 * Sent: the haptic tells which way. Otherwise the error haptic, and: already handled (the bridge's
 * 409, also for an expired id) or [settled] (the bridge client knows the request is gone) is a
 * notice, as retrying could only fail; anything else is a retry. An unreachable bridge may have
 * applied the answer all the same (a late response), so that says [UNCONFIRMED], not "not sent".
 */
internal fun actionOutcome(decision: String, sent: Sent, settled: Boolean): ActionOutcome {
    if (sent == Sent.Ok) return ActionOutcome(if (decision == Decision.DENY) Haptic.REJECT else Haptic.CONFIRM, AfterAnswer.Gone)
    val code = (sent as? Sent.Refused)?.code ?: UNCONFIRMED
    val after = when {
        code == "already_resolved" -> AfterAnswer.Notice(code)
        settled -> AfterAnswer.Notice(if (code == UNCONFIRMED) code else "already_resolved")
        else -> AfterAnswer.Retry(code)
    }
    return ActionOutcome(Haptic.ERROR, after)
}

/**
 * Allow or Deny tapped on a request notification ([Notifier.request]). The process may have been
 * started for this alone: [Bridge.init] reads the stored pairing, and the answer goes over REST
 * without a socket. The intent carries the request, so a failed answer can post it again.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val request = intent.getStringExtra(EXTRA_REQUEST)?.let {
            try {
                WireJson.decodeFromString(PendingRequest.serializer(), it)
            } catch (_: IllegalArgumentException) {
                null
            }
        } ?: return
        val question = intent.getStringExtra(EXTRA_QUESTION) ?: return
        val decision = intent.getStringExtra(EXTRA_DECISION)?.takeIf { it == Decision.ALLOW || it == Decision.DENY } ?: return
        val sessionTitle = intent.getStringExtra(EXTRA_SESSION_TITLE).orEmpty()
        val account = intent.getStringExtra(EXTRA_ACCOUNT)
        val app = context.applicationContext
        // One answer per request at a time: a second tap that got in before the actions went is dropped.
        if (!answering.add(request.id)) return
        // The actions go at once, so one decision is sent once; a retry brings them back.
        Notifier.request(app, request, sessionTitle, note = app.getString(R.string.notify_answer_sending), answerable = false, account = account)
        Bridge.init(app)
        val pending = goAsync()
        scope.launch {
            try {
                val sent = if (Bridge.prefs.isPaired) {
                    Bridge.answer(request.id, mapOf(question to listOf(decision)), ANSWER_TIMEOUT_MS)
                } else {
                    Sent.Refused("unauthorized")
                }
                // Answered on the PC meanwhile, or by this answer with its response lost.
                val settled = Bridge.conn.value == Conn.Online && Bridge.requests.value.none { it.id == request.id }
                Log.i(TAG, "action $decision id=${request.id} sent=$sent settled=$settled")
                val outcome = actionOutcome(decision, sent, settled)
                when (val after = outcome.after) {
                    AfterAnswer.Gone -> Unit
                    is AfterAnswer.Notice ->
                        Notifier.request(app, request, sessionTitle, note = note(app, after.code), answerable = false, timeoutMs = NOTICE_MS, account = account)
                    is AfterAnswer.Retry -> Notifier.request(app, request, sessionTitle, note = note(app, after.code), account = account)
                }
                // Notification usage: a process in the background may not play touch haptics.
                Haptics.event(app, outcome.haptic)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // E.g. a malformed stored address. Uncaught, it would end the process; the actions come back.
                Log.w(TAG, "action $decision id=${request.id} failed", e)
                runCatching { Notifier.request(app, request, sessionTitle, note = Notifier.answerFailed(app, "exception"), account = account) }
            } finally {
                answering.remove(request.id)
                pending.finish()
            }
        }
    }

    private fun note(context: Context, code: String): String = when (code) {
        UNCONFIRMED -> context.getString(R.string.notify_answer_unconfirmed)
        "already_resolved" -> context.getString(R.string.error_already_resolved)
        else -> Notifier.answerFailed(context, code)
    }

    companion object {
        private const val TAG = "Wristline"
        private const val ACTION_ANSWER = "dev.wristline.watch.action.ANSWER"
        private const val EXTRA_REQUEST = "request"
        private const val EXTRA_QUESTION = "question"
        private const val EXTRA_DECISION = "decision"
        private const val EXTRA_SESSION_TITLE = "sessionTitle"
        private const val EXTRA_ACCOUNT = "account"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Ids of the requests an answer is on its way for. */
        private val answering: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** The broadcast [decision] sends for [request]'s [question]; [account] is the notification's account line, if any. */
        fun intent(context: Context, request: PendingRequest, question: String, decision: String, sessionTitle: String, account: String?): Intent =
            Intent(ACTION_ANSWER)
                .setClass(context, NotificationActionReceiver::class.java)
                .setData(Uri.Builder().scheme("wristline").authority("answer").appendPath(request.id).appendPath(decision).build())
                .putExtra(EXTRA_REQUEST, WireJson.encodeToString(PendingRequest.serializer(), request))
                .putExtra(EXTRA_QUESTION, question)
                .putExtra(EXTRA_DECISION, decision)
                .putExtra(EXTRA_SESSION_TITLE, sessionTitle)
                .putExtra(EXTRA_ACCOUNT, account)
    }
}
