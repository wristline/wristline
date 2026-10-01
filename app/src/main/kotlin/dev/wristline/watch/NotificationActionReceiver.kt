package dev.wristline.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dev.wristline.watch.data.Bridge
import dev.wristline.watch.data.Decision
import dev.wristline.watch.data.Haptic
import dev.wristline.watch.data.Haptics
import dev.wristline.watch.data.PendingRequest
import dev.wristline.watch.data.Sent
import dev.wristline.watch.data.WireJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The whole answer must fit in the 10 s a foreground broadcast gets (goAsync() does not extend
 * it); the client's own timeouts (10 s connect, 20 s read) would not.
 */
private const val ANSWER_TIMEOUT_MS = 8_000L

/** What follows an action's answer: [haptic], and whether the notification is [reposted] with the error. */
internal data class ActionOutcome(val haptic: Haptic, val reposted: Boolean)

/**
 * Sent: the notification is gone (the bridge client cancelled it) and the haptic tells which way.
 * Already handled elsewhere: it is gone too, so only the error haptic. Anything else: posted again
 * with the error, so the user can try again or open the app.
 */
internal fun actionOutcome(decision: String, sent: Sent): ActionOutcome = when {
    sent == Sent.Ok -> ActionOutcome(if (decision == Decision.DENY) Haptic.REJECT else Haptic.CONFIRM, reposted = false)
    sent == Sent.Refused("already_resolved") -> ActionOutcome(Haptic.ERROR, reposted = false)
    else -> ActionOutcome(Haptic.ERROR, reposted = true)
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
        val app = context.applicationContext
        Bridge.init(app)
        val pending = goAsync()
        scope.launch {
            try {
                val sent = if (Bridge.prefs.isPaired) {
                    Bridge.answer(request.id, mapOf(question to listOf(decision)), ANSWER_TIMEOUT_MS)
                } else {
                    Sent.Refused("unauthorized")
                }
                Log.i(TAG, "action $decision id=${request.id} sent=$sent")
                val outcome = actionOutcome(decision, sent)
                if (outcome.reposted) {
                    val code = (sent as? Sent.Refused)?.code ?: "unreachable"
                    Notifier.request(app, request, sessionTitle, error = code)
                }
                // Notification usage: a process in the background may not play touch haptics.
                Haptics.event(app, outcome.haptic)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "Wristline"
        private const val ACTION_ANSWER = "dev.wristline.watch.action.ANSWER"
        private const val EXTRA_REQUEST = "request"
        private const val EXTRA_QUESTION = "question"
        private const val EXTRA_DECISION = "decision"
        private const val EXTRA_SESSION_TITLE = "sessionTitle"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** The broadcast [decision] sends for [request]'s [question]. */
        fun intent(context: Context, request: PendingRequest, question: String, decision: String, sessionTitle: String): Intent =
            Intent(ACTION_ANSWER)
                .setClass(context, NotificationActionReceiver::class.java)
                .setData(Uri.Builder().scheme("wristline").authority("answer").appendPath(request.id).appendPath(decision).build())
                .putExtra(EXTRA_REQUEST, WireJson.encodeToString(PendingRequest.serializer(), request))
                .putExtra(EXTRA_QUESTION, question)
                .putExtra(EXTRA_DECISION, decision)
                .putExtra(EXTRA_SESSION_TITLE, sessionTitle)
    }
}
