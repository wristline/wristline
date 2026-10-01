package dev.wristline.watch.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.PowerManager
import android.util.Log
import androidx.compose.runtime.Immutable
import dev.wristline.watch.Notifier
import dev.wristline.watch.R
import java.io.IOException
import java.time.Instant
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Connection state shown by the UI. */
@Immutable
sealed interface Conn {
    data object NotPaired : Conn

    /** Showing the bundled demo data instead of a bridge. */
    data object Demo : Conn

    data object Connecting : Conn

    data object Online : Conn

    /** The watch has no network; the last known lists stay visible. */
    data object Offline : Conn

    /** The bridge did not answer; the next attempt starts at [retryAt] (wall-clock millis). */
    data class Unreachable(val retryAt: Long) : Conn

    /** The token was rejected or revoked; the watch has to pair again. */
    data object Unauthorized : Conn

    data class Incompatible(val apiVersion: Int) : Conn
}

/** The loaded part of one session's transcript, oldest first. */
@Immutable
data class SessionItems(
    val items: List<Item> = emptyList(),
    /** The bridge has older items than [items]. */
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    /** The newest page has arrived at least once. */
    val loaded: Boolean = false,
    /** The newest page could not be fetched; the next page that arrives clears it. */
    val failed: Boolean = false,
) {
    val canLoadEarlier: Boolean get() = hasMore && items.isNotEmpty() && items.size < ITEM_CAP
}

/** Outcome of a request the user started (pairing, prompt, answer). */
sealed interface Sent {
    data object Ok : Sent

    /** [code] is the bridge's error code (ErrorCode or PromptBlock) or one of the local codes. */
    data class Refused(val code: String) : Sent

    data object Unreachable : Sent
}

/** Outcome of `POST /api/ask`. */
sealed interface AskSent {
    data class Started(val askId: String) : AskSent

    /** [code] is the bridge's error code (`busy`, `ask_unavailable`, ...) or one of the local codes. */
    data class Refused(val code: String) : AskSent

    data object Unreachable : AskSent
}

/** Result of `GET /api/health` without a token during onboarding. */
enum class Probe { FOUND, NOT_BRIDGE, RATE_LIMITED, UNREACHABLE }

/** Local error code: the bridge speaks another apiVersion. */
const val ERROR_INCOMPATIBLE = "incompatible"

const val PAGE_SIZE = 40

/** Upper bound of items kept in memory per open session. */
const val ITEM_CAP = 200

/** Quick Asks kept per device, newest first; the same number the bridge keeps (ASK_KEEP in its src/ask.ts). */
const val ASK_KEEP = 10

/** How long the demo "thinks" before its canned answer. */
private const val DEMO_ASK_MS = 1_500L

/** A snapshot's alert older than this is not replayed. */
private const val ALERT_REPLAY_MS = 10 * 60_000L

/** Alert ids remembered, so a snapshot after a reconnect does not replay what was already handled. */
private const val ALERT_IDS_KEPT = 50

/** The item kinds of a transcript without tool calls and notices ([Prefs.showToolCalls] off). */
val CONVERSATION_KINDS = listOf(ItemKind.USER, ItemKind.ASSISTANT)

private const val MIN_BACKOFF_MS = 1_000L
private const val MAX_BACKOFF_MS = 30_000L

/** With only the monitoring service holding the connection: later retries, fewer wake-ups. */
internal const val BACKGROUND_MAX_BACKOFF_MS = 120_000L
private const val JITTER = 0.2

/**
 * Delay before reconnect attempt number [attempt] (0-based): 1 s doubling up to [maxMs], then
 * scaled by `1 + 0.2 * jitter` where [jitter] is uniform in [-1, 1].
 */
internal fun reconnectDelayMs(attempt: Int, jitter: Double, maxMs: Long = MAX_BACKOFF_MS): Long {
    val base = min(maxMs, MIN_BACKOFF_MS shl attempt.coerceIn(0, 7))
    return (base * (1 + JITTER * jitter.coerceIn(-1.0, 1.0))).roundToLong()
}

/** How a request or alert gets the user's attention. */
internal enum class Attention { TICK, POST }

/**
 * The user is looking at the app: MainActivity is in the [foreground] (resumed), the screen is
 * [interactive], and the app is not shown in [ambient] (always-on, dimmed). From Wear OS 6 the
 * activity stays resumed in ambient, so [foreground] alone is not enough.
 */
internal fun isLooking(foreground: Boolean, interactive: Boolean, ambient: Boolean): Boolean =
    foreground && interactive && !ambient

/**
 * While the user is [looking] at the app a tick is enough: the screens show the request or the
 * waiting session. A [done] alert is posted anyway unless its [sessionId] is the one open in the
 * session screen ([openSession]), so a finished task elsewhere is not lost.
 */
internal fun attentionFor(looking: Boolean, done: Boolean, sessionId: String, openSession: String?): Attention =
    if (looking && (!done || sessionId == openSession)) Attention.TICK else Attention.POST

/**
 * The requests whose notification this process posted, until they are resolved. Going to the
 * background posts only the others ([unposted]): one already posted stays quiet even when the user
 * dismissed it. Not thread-safe; the bridge uses it on its dispatcher only.
 */
internal class PostedRequests {
    private val ids = HashSet<String>()

    /** The [requests] not posted yet. */
    fun unposted(requests: List<PendingRequest>): List<PendingRequest> = requests.filter { it.id !in ids }

    /** Posts [request] with [notify] and remembers it when that returns true; returns what [notify] did. */
    fun post(request: PendingRequest, notify: (PendingRequest) -> Boolean): Boolean =
        notify(request).also { if (it) ids += request.id }

    /** A snapshot lists every open request: the ids not among [requests] ended meanwhile. */
    fun keepOnly(requests: List<PendingRequest>) {
        ids.retainAll(requests.mapTo(HashSet()) { it.id })
    }

    /** [id] was resolved: should it come back, it is posted again. */
    fun forget(id: String) {
        ids -= id
    }

    fun clear() = ids.clear()
}

/**
 * Who holds the connection. With an activity the bridge is told `foreground` and pushes
 * everything; with only the service, `background`: requests, alerts and needs-input changes.
 */
enum class Holder { ACTIVITY, SERVICE }

private val statusOrder = listOf(SessionStatus.NEEDS_INPUT, SessionStatus.RUNNING, SessionStatus.IDLE)

/** Same order as the bridge: needs_input, running, then most recent activity. */
internal fun sortSessions(sessions: List<Session>): List<Session> =
    sessions.sortedWith(
        compareBy<Session> { statusOrder.indexOf(it.status).let { i -> if (i < 0) statusOrder.size else i } }
            .thenByDescending { isoToMillis(it.lastActivity) ?: 0L },
    )

/** Adds or replaces [item] (same seq means an update) and keeps at most [ITEM_CAP], dropping the oldest. */
internal fun SessionItems.withItem(item: Item): SessionItems {
    val list = items
    if (list.isEmpty() || item.seq > list.last().seq) {
        val grown = list + item
        if (grown.size <= ITEM_CAP) return copy(items = grown)
        return copy(items = grown.subList(grown.size - ITEM_CAP, grown.size).toList(), hasMore = true)
    }
    val index = list.binarySearch { it.seq.compareTo(item.seq) }
    if (index >= 0) return copy(items = list.toMutableList().apply { set(index, item) })
    // Older than everything held: "earlier" will fetch it when the user scrolls back.
    if (item.seq < list.first().seq) return this
    return copy(items = list.toMutableList().apply { add(-index - 1, item) })
}

/**
 * Applies the newest page after opening a session or reconnecting. When the page overlaps or
 * directly follows what is held, the two are merged so scrolled-back history survives a
 * reconnect; after a gap the page replaces everything.
 */
internal fun SessionItems.withLatest(page: ItemPage): SessionItems {
    val fresh = page.items.sortedBy { it.seq }
    if (items.isEmpty() || fresh.isEmpty() || fresh.first().seq > items.last().seq + 1) {
        val kept = fresh.takeLast(ITEM_CAP)
        return SessionItems(items = kept, hasMore = page.hasMore || kept.size < fresh.size, loaded = true)
    }
    val bySeq = TreeMap<Long, Item>()
    items.forEach { bySeq[it.seq] = it }
    fresh.forEach { bySeq[it.seq] = it }
    val all = bySeq.values.toList()
    val olderSide = if (fresh.first().seq <= items.first().seq) page.hasMore else hasMore
    return SessionItems(
        items = if (all.size > ITEM_CAP) all.subList(all.size - ITEM_CAP, all.size).toList() else all,
        hasMore = olderSide || all.size > ITEM_CAP,
        loaded = true,
    )
}

/** Prepends an older page, never growing past [ITEM_CAP]. */
internal fun SessionItems.withEarlier(page: ItemPage): SessionItems {
    val first = items.firstOrNull()?.seq ?: return withLatest(page)
    val older = page.items.filter { it.seq < first }.sortedBy { it.seq }
    val kept = older.takeLast((ITEM_CAP - items.size).coerceAtLeast(0))
    return copy(items = kept + items, hasMore = page.hasMore || kept.size < older.size, loading = false)
}

/**
 * The snapshot's alerts still worth handling: with an id (else they could be replayed again and
 * again), at most [ALERT_REPLAY_MS] old by `at`, and still true of their session, as the live
 * events would have cancelled the rest: a needs-input alert of a session that is still waiting, a
 * done alert of one that is listed and not running again.
 */
internal fun replayableAlerts(alerts: List<ServerEvent.Alert>, sessions: List<Session>, now: Long): List<ServerEvent.Alert> =
    alerts.filter { alert ->
        if (alert.id == null) return@filter false
        val at = isoToMillis(alert.at)
        if (at != null && now - at > ALERT_REPLAY_MS) return@filter false
        val session = sessions.firstOrNull { it.id == alert.sessionId } ?: return@filter false
        when (alert.alert) {
            AlertKind.NEEDS_INPUT -> session.status == SessionStatus.NEEDS_INPUT
            else -> session.status != SessionStatus.RUNNING
        }
    }

/** Remembers the handled alert [id] among the newest [ALERT_IDS_KEPT]; false when it already was. */
internal fun ArrayDeque<String>.markSeen(id: String): Boolean {
    if (id in this) return false
    addLast(id)
    if (size > ALERT_IDS_KEPT) removeFirst()
    return true
}

/**
 * Hands [handle] a snapshot's [replayableAlerts], then marks every one of [alerts] seen: the
 * filtered ones no longer apply and never will, but once their session moves on (idle again after
 * running) a later snapshot would replay one over the newer alert.
 */
internal fun ArrayDeque<String>.replayAlerts(alerts: List<ServerEvent.Alert>, sessions: List<Session>, now: Long, handle: (ServerEvent.Alert) -> Unit) {
    replayableAlerts(alerts, sessions, now).forEach(handle)
    alerts.forEach { alert -> alert.id?.let { markSeen(it) } }
}

/**
 * Applies a `usage` event: [usage] replaces the entry with the same [Usage.key], or is added. Without
 * windows it removes that entry instead (its last window has reset, or its account is no longer
 * logged in).
 */
internal fun List<Usage>.withUsage(usage: Usage): List<Usage> {
    if (usage.windows.isEmpty()) return filterNot { it.key == usage.key }
    val index = indexOfFirst { it.key == usage.key }
    return if (index < 0) this + usage else toMutableList().apply { set(index, usage) }
}

/** Replaces the ask with the same id, or puts a new one first; never more than [ASK_KEEP]. */
internal fun List<Ask>.withAsk(ask: Ask): List<Ask> {
    val index = indexOfFirst { it.id == ask.id }
    if (index >= 0) return toMutableList().apply { set(index, ask) }
    return (listOf(ask) + this).take(ASK_KEEP)
}

/**
 * Lists the ask a 202 accepted. An `ask` event can beat the response (a CLI that fails to start at
 * once): that record keeps its status and gains the question and thread only the watch knew.
 */
internal fun List<Ask>.withAccepted(ask: Ask): List<Ask> {
    val current = firstOrNull { it.id == ask.id } ?: return withAsk(ask)
    return withAsk(current.copy(question = ask.question, threadId = ask.threadId))
}

/**
 * Applies an `ask` event. An unknown askId (the bridge answered after a reconnect) gets a record
 * without its question; the next `GET /api/asks` fills it in.
 */
internal fun List<Ask>.withAskEvent(event: ServerEvent.AskChanged): List<Ask> {
    val current = firstOrNull { it.id == event.askId }
        ?: Ask(event.askId, event.provider, status = event.status, createdAt = Instant.now().toString())
    return withAsk(
        current.copy(
            status = event.status,
            answer = event.text ?: current.answer,
            model = event.model ?: current.model,
            durationMs = event.durationMs ?: current.durationMs,
            error = event.error,
        ),
    )
}

/**
 * Process-wide client for the paired bridge: one OkHttp client, one WebSocket while something
 * holds [acquire], and the state the screens observe.
 *
 * Threading: every mutation runs on [dispatcher], a single-threaded view of Dispatchers.Default, so
 * WebSocket events, REST results and lifecycle calls are applied in order without locks. Only the
 * per-session item flows are also reached from the main thread and are guarded by [lock].
 */
object Bridge {
    private const val TAG = "Wristline"

    /** Keeps the socket through short interruptions such as the text-input activity. */
    private const val LINGER_MS = 10_000L

    /** How long the requests on screen stay unposted while the user types or dictates; see [toBackground]. */
    private const val INPUT_GRACE_MS = 60_000L
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private lateinit var appContext: Context
    lateinit var prefs: Prefs
        private set

    private val _conn = MutableStateFlow<Conn>(Conn.NotPaired)
    val conn: StateFlow<Conn> = _conn.asStateFlow()
    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()
    private val _requests = MutableStateFlow<List<PendingRequest>>(emptyList())
    val requests: StateFlow<List<PendingRequest>> = _requests.asStateFlow()
    private val _usage = MutableStateFlow<List<Usage>>(emptyList())
    val usage: StateFlow<List<Usage>> = _usage.asStateFlow()

    /** This device's Quick Asks, newest first (the bridge keeps them in memory only). */
    private val _asks = MutableStateFlow<List<Ask>>(emptyList())
    val asks: StateFlow<List<Ask>> = _asks.asStateFlow()

    // Items exist only for sessions a screen shows; the last closeSession() drops them.
    private val lock = Any()
    private val itemFlows = HashMap<String, MutableStateFlow<SessionItems>>()
    private val openCounts = HashMap<String, Int>()

    // Confined to [dispatcher].
    private var users = 0
    private var activityUsers = 0

    /** The mode last applied: true while only the service holds the connection. */
    private var background = false

    /** The open socket was made with [quietClient]. */
    private var quietSocket = false
    private var quietJob: Job? = null

    /** The socket is being replaced by a quiet one on purpose: its close is not a failure. */
    private var swapping = false
    private var refreshJob: Job? = null
    private var lingerJob: Job? = null
    private var connectionJob: Job? = null
    private var socket: WebSocket? = null
    private var subscribed: String? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var networkUp = true
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var demo: DemoData? = null
    private val latestJobs = HashMap<String, Job>()
    private val seenAlertIds = ArrayDeque<String>()

    private val postedRequests = PostedRequests()

    /** Posts the requests on screen when the app went to the background; see [toBackground]. */
    private var backgroundJob: Job? = null

    /** MainActivity is resumed and the screen is on; see [looking]. */
    @Volatile
    var foreground = false

    /** MainActivity is shown in ambient (always-on, dimmed), as the ambient API last said; see [looking]. */
    @Volatile
    var ambient = false

    /**
     * Set on the main thread right after the app opened the system text or speech input over
     * MainActivity, which pauses it next; see [toBackground].
     */
    @Volatile
    var inputOpening = false

    /**
     * The user is looking at the app ([isLooking]): requests then only tick the vibrator (the
     * screens show them); otherwise they become notifications.
     */
    private fun looking(): Boolean =
        isLooking(foreground, appContext.getSystemService(PowerManager::class.java).isInteractive, ambient)

    /**
     * MainActivity is paused, or went ambient: the requests its screens were showing (only
     * ticked) become notifications. Idempotent: the ones already posted are not posted again.
     * Paused for the app's own text or speech input ([inputOpening]), the user has not left: they
     * wait [INPUT_GRACE_MS], and [toForeground] on the way back drops them.
     */
    fun toBackground() {
        foreground = false
        val delayMs = if (inputOpening) INPUT_GRACE_MS else 0L
        inputOpening = false
        scope.launch {
            backgroundJob?.cancel()
            backgroundJob = scope.launch {
                delay(delayMs)
                if (demo != null) return@launch
                postedRequests.unposted(_requests.value).forEach { postRequest(it) }
            }
        }
    }

    /** MainActivity is resumed: requests [toBackground] has not posted yet stay unposted. */
    fun toForeground() {
        foreground = true
        inputOpening = false
        scope.launch {
            backgroundJob?.cancel()
            backgroundJob = null
        }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    /** For the background socket: a third of the pings. Shares the connection pool and threads of [client]. */
    private val quietClient: OkHttpClient by lazy { client.newBuilder().pingInterval(90, TimeUnit.SECONDS).build() }

    /** Idempotent; call before reading any state. */
    fun init(context: Context) {
        // On every call: an activity recreated for a new app language renames the channels.
        Notifier.createChannels(context)
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = Prefs(appContext)
        if (prefs.isPaired) {
            _conn.value = Conn.Connecting
        } else if (prefs.demo) {
            // Like a pairing, the demo outlives the process: screens restored after its death expect it.
            scope.launch { startDemo() }
        }
    }

    // ---- lifecycle ----

    /** An activity or the monitoring service needs a live connection. */
    fun acquire(holder: Holder) {
        scope.launch {
            users++
            if (holder == Holder.ACTIVITY) activityUsers++
            lingerJob?.cancel()
            lingerJob = null
            if (networkCallback == null) registerNetworkCallback()
            connectNow()
            applyMode()
        }
    }

    fun release(holder: Holder) {
        scope.launch {
            users = (users - 1).coerceAtLeast(0)
            if (holder == Holder.ACTIVITY) activityUsers = (activityUsers - 1).coerceAtLeast(0)
            // With nothing left holding it the socket is closed by the linger, not swapped for a quiet one.
            if (users > 0) {
                applyMode()
                return@launch
            }
            lingerJob = scope.launch {
                delay(LINGER_MS)
                unregisterNetworkCallback()
                stopConnection()
                val c = _conn.value
                if (c is Conn.Online || c is Conn.Offline || c is Conn.Unreachable) _conn.value = Conn.Connecting
            }
        }
    }

    /**
     * Tells the bridge the mode the holders imply when it changes. Going to the background, the
     * socket (pinging every 30 s) is replaced after a pause by one made with [quietClient]; the
     * pause covers an activity that comes right back. Coming to the foreground, the lists are
     * fetched again: the bridge kept quiet about them meanwhile.
     */
    private fun applyMode() {
        val quiet = activityUsers == 0
        if (quiet == background) return
        background = quiet
        socket?.send(modeMessage(quiet))
        quietJob?.cancel()
        quietJob = null
        if (quiet) {
            if (socket != null) scheduleQuietSwap()
        } else {
            refresh()
        }
    }

    /** After the pause, replaces a foreground socket that is still the open one with a quiet one. */
    private fun scheduleQuietSwap() {
        if (quietSocket) return
        quietJob?.cancel()
        quietJob = scope.launch {
            delay(LINGER_MS)
            if (!background || quietSocket) return@launch
            socket?.let {
                swapping = true
                it.cancel()
            }
        }
    }

    /**
     * Fetches the session, usage and ask lists over REST. A background client is not sent their
     * changes (nor an ask's answer), so coming to the foreground reads the current state; a
     * snapshot does the same after a reconnect. One fetch at a time; nothing while the socket is
     * down (the snapshot will tell) or in the demo.
     */
    fun refresh() {
        scope.launch {
            if (demo != null || _conn.value !is Conn.Online || refreshJob?.isActive == true) return@launch
            refreshJob = scope.launch {
                get(SessionList.serializer(), "sessions")?.let { _sessions.value = sortSessions(it.sessions) }
                get(UsageList.serializer(), "usage")?.let { _usage.value = it.usage }
                loadAsks()
            }
        }
    }

    /** Skips the remaining backoff (Retry button). Also leaves Incompatible, e.g. after a bridge update. */
    fun retryNow() {
        scope.launch {
            if (_conn.value is Conn.Incompatible) _conn.value = Conn.Connecting
            connectNow()
        }
    }

    private fun connectNow() {
        if (users == 0 || demo != null || !prefs.isPaired) return
        val c = _conn.value
        if (c is Conn.Unauthorized || c is Conn.Incompatible) return
        if (connectionJob?.isActive == true) {
            wake.trySend(Unit)
        } else {
            connectionJob = scope.launch { connectionLoop() }
        }
    }

    private fun stopConnection() {
        connectionJob?.cancel()
        connectionJob = null
        quietJob?.cancel()
        quietJob = null
        swapping = false
        socket = null
    }

    private fun registerNetworkCallback() {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        networkUp = cm.activeNetwork != null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    networkUp = true
                    connectNow()
                }
            }

            override fun onLost(network: Network) {
                scope.launch {
                    networkUp = false
                    // The socket belonged to the lost network; fail it now rather than at the next ping.
                    socket?.cancel()
                }
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        appContext.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        networkCallback = null
    }

    // ---- WebSocket ----

    private enum class End { CLOSED, UNAUTHORIZED, INCOMPATIBLE }

    private suspend fun connectionLoop() {
        var attempt = 0
        while (true) {
            if (!networkUp) {
                _conn.value = Conn.Offline
                wake.receive()
                continue
            }
            if (_conn.value !is Conn.Online) _conn.value = Conn.Connecting
            when (connectOnce(onOnline = { attempt = 0 })) {
                End.UNAUTHORIZED -> {
                    _conn.value = Conn.Unauthorized
                    return
                }
                End.INCOMPATIBLE -> return
                End.CLOSED -> Unit
            }
            if (swapping) {
                // The quiet socket replaces the foreground one at once, without a Connecting blip.
                swapping = false
                continue
            }
            if (!networkUp) continue
            val cap = if (background) BACKGROUND_MAX_BACKOFF_MS else MAX_BACKOFF_MS
            val wait = reconnectDelayMs(attempt++, Random.nextDouble(-1.0, 1.0), cap)
            // A single drop retries quietly; repeated failures show the countdown.
            _conn.value = if (attempt == 1) Conn.Connecting else Conn.Unreachable(System.currentTimeMillis() + wait)
            withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    private suspend fun connectOnce(onOnline: () -> Unit): End {
        val ended = CompletableDeferred<End>()
        // stopConnection() cancels this job before the socket has closed; its late callbacks are dropped.
        val job = currentCoroutineContext().job
        val request = Request.Builder()
            .url(apiUrl(prefs.baseUrl, "ws"))
            .header("Authorization", "Bearer ${prefs.token}")
            .build()
        val quiet = background
        quietSocket = quiet
        val ws = (if (quiet) quietClient else client).newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    scope.launch {
                        if (ended.isCompleted || !job.isActive) return@launch
                        socket = webSocket
                        subscribed?.let { webSocket.send(subscribeMessage(it, itemKinds())) }
                        // The bridge starts every socket in the foreground mode.
                        webSocket.send(modeMessage(background))
                        // Gone to the background while this foreground socket was still connecting.
                        if (background) scheduleQuietSwap()
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    scope.launch { if (!ended.isCompleted && job.isActive) onEvent(text, ended, onOnline) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    ended.complete(if (code == CLOSE_REVOKED) End.UNAUTHORIZED else End.CLOSED)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    ended.complete(End.CLOSED)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    ended.complete(if (response?.code == 401) End.UNAUTHORIZED else End.CLOSED)
                }
            },
        )
        try {
            return ended.await()
        } finally {
            socket = null
            ws.close(1000, null)
        }
    }

    private fun onEvent(text: String, ended: CompletableDeferred<End>, onOnline: () -> Unit) {
        val event = try {
            parseServerEvent(text)
        } catch (_: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException. Never log the payload (transcript text).
            Log.w(TAG, "Skipped a malformed event")
            null
        } ?: return
        when (event) {
            is ServerEvent.Snapshot -> {
                if (event.apiVersion != API_VERSION) {
                    _conn.value = Conn.Incompatible(event.apiVersion)
                    ended.complete(End.INCOMPATIBLE)
                    return
                }
                val known = _requests.value.mapTo(HashSet()) { it.id }
                _sessions.value = sortSessions(event.sessions)
                _requests.value = event.requests
                postedRequests.keepOnly(event.requests)
                _usage.value = event.usage
                _conn.value = Conn.Online
                // Requests that arrived or ended while the socket was down.
                Notifier.reconcile(appContext, event.requests, event.sessions)
                val looking = looking()
                event.requests.filter { it.id !in known }.forEach {
                    logReceived("request", it.id, looking, posted = !looking && postRequest(it))
                }
                // Alerts the closed socket missed, oldest first; the ones already handled are skipped.
                seenAlertIds.replayAlerts(event.alerts, event.sessions, System.currentTimeMillis(), ::onAlert)
                onOnline()
                // The subscription alone does not replay what the socket missed.
                subscribed?.let { loadLatest(it) }
                // Asks are not in the snapshot; an answer that arrived while the socket was down is
                // fetched, unless nothing is shown (the service alone never polls).
                if (!background) scope.launch { loadAsks() }
            }
            is ServerEvent.SessionChanged -> {
                _sessions.value = sortSessions(_sessions.value.filterNot { it.id == event.session.id } + event.session)
                // Working again: its needs-input or done alert is stale; no longer waiting (answered or
                // dismissed on the PC): its needs-input alert is. (An ended one is removed.)
                if (event.session.status != SessionStatus.NEEDS_INPUT) Notifier.cancelStale(appContext, event.session)
            }
            is ServerEvent.SessionRemoved -> {
                _sessions.value = _sessions.value.filterNot { it.id == event.sessionId }
                Notifier.cancelSession(appContext, event.sessionId)
            }
            is ServerEvent.ItemChanged -> itemFlow(event.sessionId)?.update { it.withItem(event.item) }
            is ServerEvent.RequestAdded -> {
                val isNew = _requests.value.none { it.id == event.request.id }
                _requests.value = _requests.value.filterNot { it.id == event.request.id } + event.request
                if (isNew) {
                    val looking = looking()
                    val posted = attention(looking, event.request.sessionId) { postRequest(event.request) }
                    logReceived("request", event.request.id, looking, posted)
                }
            }
            is ServerEvent.Resolved -> removeRequest(event.requestId)
            is ServerEvent.UsageChanged -> _usage.value = _usage.value.withUsage(event.usage)
            is ServerEvent.Alert -> onAlert(event)
            is ServerEvent.AskChanged -> {
                val before = _asks.value.firstOrNull { it.id == event.askId }?.status
                _asks.update { it.withAskEvent(event) }
                // Only while the user looks: the Ask screen shows the answer, and no notification stands in.
                askHaptic(before, event)?.let { if (looking()) Haptics.event(appContext, it) }
            }
        }
    }

    /** A live alert, or one replayed by a snapshot; each id is handled once. */
    private fun onAlert(event: ServerEvent.Alert) {
        val id = event.id
        if (id != null && !seenAlertIds.markSeen(id)) return
        val looking = looking()
        val posted = when (event.alert) {
            AlertKind.NEEDS_INPUT -> _requests.value.none { it.sessionId == event.sessionId } &&
                attention(looking, event.sessionId) { Notifier.needsInput(appContext, event.sessionId, event.text, session(event.sessionId)) }
            AlertKind.DONE -> attention(looking, event.sessionId, done = true) {
                Notifier.done(appContext, event.sessionId, event.title, event.text, session(event.sessionId))
            }
            else -> return
        }
        logReceived("alert ${event.alert}", id, looking, posted)
    }

    /**
     * A haptic ([Haptic.ATTENTION], [Haptic.DONE] for a [done] alert) or [post] a notification, as
     * [attentionFor] decides; true when posted.
     */
    private inline fun attention(looking: Boolean, sessionId: String, done: Boolean = false, post: () -> Boolean): Boolean =
        when (attentionFor(looking, done, sessionId, subscribed)) {
            Attention.TICK -> {
                Haptics.event(appContext, if (done) Haptic.DONE else Haptic.ATTENTION)
                false
            }
            Attention.POST -> post()
        }

    /** Payload-free: never a title or text. */
    private fun logReceived(what: String, id: String?, looking: Boolean, posted: Boolean) {
        Log.i(TAG, "$what recv id=$id looking=$looking posted=$posted")
    }

    private fun session(id: String): Session? = _sessions.value.firstOrNull { it.id == id }

    /** Posts [request]'s notification and remembers it in [postedRequests]; true when posted. */
    private fun postRequest(request: PendingRequest): Boolean =
        postedRequests.post(request) { Notifier.request(appContext, it, session(it.sessionId)) }

    private fun removeRequest(id: String) {
        _requests.update { list -> list.filterNot { it.id == id } }
        postedRequests.forget(id)
        Notifier.cancelRequest(appContext, id)
    }

    private fun unauthorized() {
        _conn.value = Conn.Unauthorized
        stopConnection()
    }

    // ---- session items ----

    /** Starts showing a session: holds its items until the matching [closeSession]; [watchSession] fills them. */
    fun openSession(id: String): StateFlow<SessionItems> =
        synchronized(lock) {
            openCounts[id] = (openCounts[id] ?: 0) + 1
            itemFlows.getOrPut(id) { MutableStateFlow(SessionItems()) }
        }.asStateFlow()

    /**
     * The screen showing [id] is started: subscribes to its live items and loads the newest page,
     * which also catches up on what was missed while the screen was stopped.
     */
    fun watchSession(id: String) {
        scope.launch {
            subscribe(id)
            // Otherwise the next snapshot loads it.
            if (_conn.value is Conn.Online || demo != null) loadLatest(id)
        }
    }

    /** The screen is stopped (screen off, another activity in front): nothing streams until it is watched again. */
    fun unwatchSession(id: String) {
        scope.launch { if (subscribed == id) subscribe(null) }
    }

    /** Pairs with [openSession]; the last close releases the items. */
    fun closeSession(id: String) {
        val released = synchronized(lock) {
            val remaining = (openCounts[id] ?: 1) - 1
            if (remaining > 0) {
                openCounts[id] = remaining
                false
            } else {
                openCounts.remove(id)
                itemFlows.remove(id)
                true
            }
        }
        if (released) {
            scope.launch {
                latestJobs.remove(id)?.cancel()
                if (subscribed == id) subscribe(null)
            }
        }
    }

    fun loadEarlier(id: String) {
        scope.launch {
            val flow = itemFlow(id) ?: return@launch
            val current = flow.value
            if (current.loading || !current.canLoadEarlier) return@launch
            flow.update { it.copy(loading = true) }
            val page = fetchItems(id, before = current.items.first().seq)
            itemFlow(id)?.update { if (page == null) it.copy(loading = false) else it.withEarlier(page) }
        }
    }

    private fun itemFlow(id: String): MutableStateFlow<SessionItems>? = synchronized(lock) { itemFlows[id] }

    private fun subscribe(id: String?) {
        if (subscribed == id) return
        subscribed = id
        socket?.send(subscribeMessage(id, id?.let { itemKinds() }))
    }

    /**
     * The item kinds to ask for, null for all. Read on every request, so a changed setting applies
     * from the next opened session on (Settings is never shown over an open one).
     */
    private fun itemKinds(): List<String>? = if (prefs.showToolCalls) null else CONVERSATION_KINDS

    /** Loads the newest page. A fetch still in flight is replaced, so a reconnect never skips the reload. */
    private fun loadLatest(id: String) {
        val flow = itemFlow(id) ?: return
        latestJobs[id]?.cancel()
        latestJobs[id] = scope.launch {
            flow.update { it.copy(loading = true) }
            val page = fetchItems(id, before = null)
            itemFlow(id)?.update { if (page == null) it.copy(loading = false, failed = true) else it.withLatest(page) }
        }
    }

    private suspend fun fetchItems(id: String, before: Long?): ItemPage? {
        val kinds = itemKinds()
        demo?.let { data ->
            // The demo filters locally what a bridge filters for the `kinds` parameter.
            val items = if (before == null) data.items[id].orEmpty() else emptyList()
            return ItemPage(if (kinds == null) items else items.filter { it.kind in kinds })
        }
        val url = apiUrl(prefs.baseUrl, "sessions", id, "items").newBuilder()
            .apply { if (before != null) addQueryParameter("before", before.toString()) }
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .apply { if (kinds != null) addQueryParameter("kinds", kinds.joinToString(",")) }
            .build()
        val result = send(authed(url).get().build()) ?: return null
        if (result.code == 401) unauthorized()
        if (result.code != 200) return null
        return decodeOrNull(ItemPage.serializer(), result.body)
    }

    // ---- pairing and actions ----

    /** A bridge answers an unauthenticated health check with 401 and its own realm. */
    suspend fun probe(baseUrl: String): Probe = withContext(dispatcher) {
        val result = send(Request.Builder().url(apiUrl(baseUrl, "health")).build())
            ?: return@withContext Probe.UNREACHABLE
        val challenge = result.authenticate.orEmpty()
        when {
            result.code == 401 && challenge.startsWith("Bearer", ignoreCase = true) &&
                "realm=\"wristline\"" in challenge -> Probe.FOUND
            result.code == 429 -> Probe.RATE_LIMITED
            else -> Probe.NOT_BRIDGE
        }
    }

    // Like prompt() and answer() below, pair() and disconnect() finish even when their screen is
    // swiped away: the bridge consumes the code or revokes the device as soon as the request is in.
    suspend fun pair(baseUrl: String, code: String): Sent = withContext(dispatcher + NonCancellable) {
        val body = WireJson.encodeToString(PairRequest.serializer(), PairRequest(code, prefs.deviceName))
        val result = send(Request.Builder().url(apiUrl(baseUrl, "pair")).post(body.toRequestBody(jsonType)).build())
            ?: return@withContext Sent.Unreachable
        if (result.code != 200) return@withContext Sent.Refused(errorCode(result))
        val response = decodeOrNull(PairResponse.serializer(), result.body)
            ?: return@withContext Sent.Refused("bad_response")
        if (response.bridge.apiVersion != API_VERSION) return@withContext Sent.Refused(ERROR_INCOMPATIBLE)
        startPaired(baseUrl, response.token, response.deviceId)
        Sent.Ok
    }

    /** Token printed by `wristline-bridge pair --token`; a wrong one ends in [Conn.Unauthorized]. */
    fun useToken(baseUrl: String, token: String) {
        scope.launch { startPaired(baseUrl, token, deviceId = "") }
    }

    private fun startPaired(baseUrl: String, token: String, deviceId: String) {
        stopConnection()
        demo = null
        clearState()
        prefs.savePairing(baseUrl, normalizeToken(token), deviceId)
        // A typed token with nothing usable left is as wrong as one the bridge rejects.
        _conn.value = if (prefs.isPaired) Conn.Connecting else Conn.Unauthorized
        connectNow()
    }

    // prompt() and answer() finish even when the screen that sent them closes: the user has already
    // confirmed them, and cancelling the call midway could drop a dictated prompt or an answer.
    suspend fun prompt(sessionId: String, text: String): Sent = withContext(dispatcher + NonCancellable) {
        if (demo != null) {
            itemFlow(sessionId)?.update {
                it.withItem(Item((it.items.lastOrNull()?.seq ?: 0L) + 1, ItemKind.USER, Instant.now().toString(), text))
            }
            return@withContext Sent.Ok
        }
        val body = WireJson.encodeToString(PromptBody.serializer(), PromptBody(text))
        val url = apiUrl(prefs.baseUrl, "sessions", sessionId, "prompt")
        val result = send(authed(url).post(body.toRequestBody(jsonType)).build()) ?: return@withContext Sent.Unreachable
        when (result.code) {
            200, 202 -> Sent.Ok
            401 -> {
                unauthorized()
                Sent.Refused("unauthorized")
            }
            else -> Sent.Refused(errorCode(result))
        }
    }

    /** [timeoutMs], when not 0, bounds the whole call (a notification action must finish within 10 s). */
    suspend fun answer(requestId: String, answers: Answers, timeoutMs: Long = 0): Sent = withContext(dispatcher + NonCancellable) {
        if (demo != null) {
            demoAnswer(requestId)
            return@withContext Sent.Ok
        }
        val body = WireJson.encodeToString(AnswerBody.serializer(), AnswerBody(answers))
        val url = apiUrl(prefs.baseUrl, "requests", requestId)
        val result = send(authed(url).post(body.toRequestBody(jsonType)).build(), timeoutMs) ?: return@withContext Sent.Unreachable
        when (result.code) {
            200 -> {
                removeRequest(requestId)
                Sent.Ok
            }
            401 -> {
                unauthorized()
                Sent.Refused("unauthorized")
            }
            409 -> {
                removeRequest(requestId)
                Sent.Refused("already_resolved")
            }
            else -> Sent.Refused(errorCode(result))
        }
    }

    // ---- Quick Ask ----

    /**
     * Sends one question to the PC's CLI, continuing [threadId]'s conversation or starting a new
     * thread. Finishes even when the confirm dialog's screen closes, like prompt(). On 202 the ask
     * is listed as running at once; the `ask` events move it on.
     */
    suspend fun ask(provider: String, text: String, threadId: String? = null): AskSent = withContext(dispatcher + NonCancellable) {
        if (demo != null) return@withContext AskSent.Started(demoAsk(provider, text, threadId))
        val body = WireJson.encodeToString(AskBody.serializer(), AskBody(provider, text, threadId = threadId))
        val result = send(authed(apiUrl(prefs.baseUrl, "ask")).post(body.toRequestBody(jsonType)).build())
            ?: return@withContext AskSent.Unreachable
        when (result.code) {
            202 -> {
                val accepted = decodeOrNull(AskAccepted.serializer(), result.body)
                    ?: return@withContext AskSent.Refused("bad_response")
                val ask = Ask(
                    accepted.askId, provider, text, AskStatus.RUNNING,
                    createdAt = Instant.now().toString(), threadId = threadId ?: accepted.askId,
                )
                _asks.update { it.withAccepted(ask) }
                AskSent.Started(ask.id)
            }
            401 -> {
                unauthorized()
                AskSent.Refused("unauthorized")
            }
            else -> AskSent.Refused(errorCode(result))
        }
    }

    /** `DELETE /api/asks/:id`; the outcome arrives as an `ask` event (`error: cancelled`). */
    fun cancelAsk(id: String): Job =
        scope.launch {
            if (demo != null) {
                _asks.update { list ->
                    val ask = list.firstOrNull { it.id == id } ?: return@update list
                    if (ask.status != AskStatus.RUNNING) list else list.withAsk(ask.copy(status = AskStatus.ERROR, error = "cancelled"))
                }
                return@launch
            }
            send(authed(apiUrl(prefs.baseUrl, "asks", id)).delete().build())
        }

    /**
     * `DELETE /api/asks/thread/:threadId`: the bridge forgets the thread's asks and the CLI's
     * session; the watch drops them once it has, or when they were already gone.
     */
    suspend fun deleteThread(threadId: String): Sent = withContext(dispatcher + NonCancellable) {
        if (demo == null) {
            val result = send(authed(apiUrl(prefs.baseUrl, "asks", "thread", threadId)).delete().build())
                ?: return@withContext Sent.Unreachable
            if (result.code == 401) {
                unauthorized()
                return@withContext Sent.Refused("unauthorized")
            }
            if (result.code != 200 && result.code != 204 && result.code != 404) return@withContext Sent.Refused(errorCode(result))
        }
        _asks.update { list -> list.filterNot { it.thread == threadId } }
        Sent.Ok
    }

    private suspend fun loadAsks() {
        val list = get(AskList.serializer(), "asks") ?: return
        _asks.value = list.asks.take(ASK_KEEP)
    }

    /** Unregisters this watch on the bridge when reachable, then forgets the pairing either way. */
    suspend fun disconnect() = withContext(dispatcher + NonCancellable) {
        if (demo == null && prefs.isPaired) send(authed(apiUrl(prefs.baseUrl, "device")).delete().build())
        stopConnection()
        demo = null
        clearState()
        prefs.clear()
        _conn.value = Conn.NotPaired
    }

    private fun clearState() {
        _sessions.value = emptyList()
        _requests.value = emptyList()
        postedRequests.clear()
        _usage.value = emptyList()
        _asks.value = emptyList()
        synchronized(lock) { itemFlows.values.forEach { it.value = SessionItems() } }
        Notifier.reconcile(appContext, emptyList(), emptyList())
    }

    // ---- demo ----

    suspend fun startDemo() = withContext(dispatcher) {
        stopConnection()
        clearState()
        val data = Demo.load(appContext)
        demo = data
        prefs.demo = true
        _sessions.value = sortSessions(data.sessions)
        _requests.value = data.requests
        _usage.value = data.usage
        _conn.value = Conn.Demo
    }

    fun stopDemo() {
        scope.launch {
            prefs.demo = false
            if (demo == null) return@launch
            demo = null
            clearState()
            _conn.value = if (prefs.isPaired) Conn.Connecting else Conn.NotPaired
            connectNow()
        }
    }

    private fun demoAnswer(requestId: String) {
        val request = _requests.value.firstOrNull { it.id == requestId } ?: return
        removeRequest(requestId)
        if (_requests.value.any { it.sessionId == request.sessionId }) return
        _sessions.value = sortSessions(
            _sessions.value.map {
                if (it.id == request.sessionId) it.copy(status = SessionStatus.RUNNING, promptBlock = null) else it
            },
        )
    }

    /** A canned answer after a short "thinking" pause; the answer follows the app language. */
    private fun demoAsk(provider: String, text: String, threadId: String?): String {
        val id = "ask-demo-" + System.currentTimeMillis()
        _asks.update {
            it.withAsk(Ask(id, provider, text, AskStatus.RUNNING, createdAt = Instant.now().toString(), threadId = threadId ?: id))
        }
        scope.launch {
            delay(DEMO_ASK_MS)
            _asks.update { list ->
                val ask = list.firstOrNull { it.id == id } ?: return@update list
                if (ask.status != AskStatus.RUNNING) return@update list
                val model = if (provider == ProviderId.CLAUDE_CODE) "Haiku 4.5" else null
                list.withAsk(
                    ask.copy(
                        status = AskStatus.DONE,
                        answer = appContext.getString(R.string.demo_ask_answer),
                        model = model,
                        durationMs = DEMO_ASK_MS,
                    ),
                )
            }
        }
        return id
    }

    // ---- HTTP ----

    private class HttpResult(val code: Int, val body: String, val authenticate: String?)

    /**
     * Null when the bridge could not be reached, or not within [timeoutMs] when that is not 0. The
     * timeout is the coroutine's, not the call's: cancelling a call does not end a host name lookup
     * stuck in DNS, and its failure would only come once the lookup returns.
     */
    private suspend fun send(request: Request, timeoutMs: Long = 0): HttpResult? =
        if (timeoutMs > 0) withTimeoutOrNull(timeoutMs) { execute(request) } else execute(request)

    private suspend fun execute(request: Request): HttpResult? = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resume(null)

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { HttpResult(it.code, it.body.string(), it.header("WWW-Authenticate")) }
                    } catch (_: IOException) {
                        null
                    }
                    cont.resume(result)
                }
            },
        )
    }

    /** An authenticated GET decoded as [serializer]; null when unreachable, refused or malformed. */
    private suspend fun <T> get(serializer: KSerializer<T>, vararg segments: String): T? {
        val result = send(authed(apiUrl(prefs.baseUrl, *segments)).get().build()) ?: return null
        if (result.code == 401) unauthorized()
        if (result.code != 200) return null
        return decodeOrNull(serializer, result.body)
    }

    private fun apiUrl(baseUrl: String, vararg segments: String): HttpUrl =
        baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("api")
            .apply { segments.forEach { addPathSegment(it) } }
            .build()

    private fun authed(url: HttpUrl): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer ${prefs.token}")

    private fun errorCode(result: HttpResult): String =
        decodeOrNull(ApiError.serializer(), result.body)?.error ?: when (result.code) {
            400 -> "bad_request"
            401 -> "unauthorized"
            404 -> "not_found"
            413 -> "payload_too_large"
            429 -> "rate_limited"
            else -> "http_${result.code}"
        }

    private fun <T> decodeOrNull(serializer: KSerializer<T>, body: String): T? =
        try {
            WireJson.decodeFromString(serializer, body)
        } catch (_: IllegalArgumentException) {
            null
        }
}
