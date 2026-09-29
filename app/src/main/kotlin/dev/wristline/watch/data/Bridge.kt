package dev.wristline.watch.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.compose.runtime.Immutable
import dev.wristline.watch.Notifier
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

/** Result of `GET /api/health` without a token during onboarding. */
enum class Probe { FOUND, NOT_BRIDGE, RATE_LIMITED, UNREACHABLE }

/** Local error code: the bridge speaks another apiVersion. */
const val ERROR_INCOMPATIBLE = "incompatible"

const val PAGE_SIZE = 40

/** Upper bound of items kept in memory per open session. */
const val ITEM_CAP = 200

private const val MIN_BACKOFF_MS = 1_000L
private const val MAX_BACKOFF_MS = 30_000L
private const val JITTER = 0.2

/**
 * Delay before reconnect attempt number [attempt] (0-based): 1 s doubling up to 30 s, then scaled
 * by `1 + 0.2 * jitter` where [jitter] is uniform in [-1, 1].
 */
internal fun reconnectDelayMs(attempt: Int, jitter: Double): Long {
    val base = min(MAX_BACKOFF_MS, MIN_BACKOFF_MS shl attempt.coerceIn(0, 5))
    return (base * (1 + JITTER * jitter.coerceIn(-1.0, 1.0))).roundToLong()
}

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

    // Items exist only for sessions a screen shows; the last closeSession() drops them.
    private val lock = Any()
    private val itemFlows = HashMap<String, MutableStateFlow<SessionItems>>()
    private val openCounts = HashMap<String, Int>()

    // Confined to [dispatcher].
    private var users = 0
    private var lingerJob: Job? = null
    private var connectionJob: Job? = null
    private var socket: WebSocket? = null
    private var subscribed: String? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var networkUp = true
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var demo: DemoData? = null
    private val latestJobs = HashMap<String, Job>()

    /**
     * MainActivity is resumed, i.e. the user is looking at the app. Requests then only tick the
     * vibrator (the screens show them); otherwise they become notifications.
     */
    @Volatile
    var foreground = false

    /** MainActivity is paused: the requests its screens were showing become notifications. */
    fun toBackground() {
        foreground = false
        scope.launch {
            if (demo != null) return@launch
            _requests.value.forEach { Notifier.request(appContext, it, session(it.sessionId)) }
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

    /** Something visible (an activity, later the monitoring service) needs a live connection. */
    fun acquire() {
        scope.launch {
            users++
            lingerJob?.cancel()
            lingerJob = null
            if (networkCallback == null) registerNetworkCallback()
            connectNow()
        }
    }

    fun release() {
        scope.launch {
            users = (users - 1).coerceAtLeast(0)
            if (users > 0) return@launch
            lingerJob = scope.launch {
                delay(LINGER_MS)
                unregisterNetworkCallback()
                stopConnection()
                val c = _conn.value
                if (c is Conn.Online || c is Conn.Offline || c is Conn.Unreachable) _conn.value = Conn.Connecting
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
            if (!networkUp) continue
            val wait = reconnectDelayMs(attempt++, Random.nextDouble(-1.0, 1.0))
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
        val ws = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    scope.launch {
                        if (ended.isCompleted || !job.isActive) return@launch
                        socket = webSocket
                        subscribed?.let { webSocket.send(subscribeMessage(it)) }
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
                _usage.value = event.usage
                _conn.value = Conn.Online
                // Requests that arrived or ended while the socket was down.
                Notifier.reconcile(appContext, event.requests, event.sessions)
                if (!foreground) {
                    event.requests.filter { it.id !in known }.forEach { Notifier.request(appContext, it, session(it.sessionId)) }
                }
                onOnline()
                // The subscription alone does not replay what the socket missed.
                subscribed?.let { loadLatest(it) }
            }
            is ServerEvent.SessionChanged -> {
                _sessions.value = sortSessions(_sessions.value.filterNot { it.id == event.session.id } + event.session)
                // Working again or over: its needs-input or done alert is stale.
                val status = event.session.status
                if (status == SessionStatus.RUNNING || status == SessionStatus.ENDED) Notifier.cancelSession(appContext, event.session.id)
            }
            is ServerEvent.SessionRemoved -> {
                _sessions.value = _sessions.value.filterNot { it.id == event.sessionId }
                Notifier.cancelSession(appContext, event.sessionId)
            }
            is ServerEvent.ItemChanged -> itemFlow(event.sessionId)?.update { it.withItem(event.item) }
            is ServerEvent.RequestAdded -> {
                val isNew = _requests.value.none { it.id == event.request.id }
                _requests.value = _requests.value.filterNot { it.id == event.request.id } + event.request
                if (isNew) attention { Notifier.request(appContext, event.request, session(event.request.sessionId)) }
            }
            is ServerEvent.Resolved -> removeRequest(event.requestId)
            is ServerEvent.UsageChanged -> {
                val list = _usage.value
                val index = list.indexOfFirst { it.key == event.usage.key }
                _usage.value = if (index < 0) list + event.usage else list.toMutableList().apply { set(index, event.usage) }
            }
            is ServerEvent.Alert -> when (event.alert) {
                AlertKind.NEEDS_INPUT -> if (_requests.value.none { it.sessionId == event.sessionId }) {
                    attention { Notifier.needsInput(appContext, event.sessionId, event.text, session(event.sessionId)) }
                }
                AlertKind.DONE -> if (!foreground) Notifier.done(appContext, event.sessionId, event.text, session(event.sessionId))
            }
        }
    }

    /** In the foreground a single vibration tick; otherwise [post] a notification. */
    private inline fun attention(post: () -> Unit) {
        if (foreground) Notifier.tick(appContext) else post()
    }

    private fun session(id: String): Session? = _sessions.value.firstOrNull { it.id == id }

    private fun removeRequest(id: String) {
        _requests.update { list -> list.filterNot { it.id == id } }
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
        socket?.send(subscribeMessage(id))
    }

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
        demo?.let { return ItemPage(if (before == null) it.items[id].orEmpty() else emptyList()) }
        val url = apiUrl(prefs.baseUrl, "sessions", id, "items").newBuilder()
            .apply { if (before != null) addQueryParameter("before", before.toString()) }
            .addQueryParameter("limit", PAGE_SIZE.toString())
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

    suspend fun answer(requestId: String, answers: Answers): Sent = withContext(dispatcher + NonCancellable) {
        if (demo != null) {
            demoAnswer(requestId)
            return@withContext Sent.Ok
        }
        val body = WireJson.encodeToString(AnswerBody.serializer(), AnswerBody(answers))
        val url = apiUrl(prefs.baseUrl, "requests", requestId)
        val result = send(authed(url).post(body.toRequestBody(jsonType)).build()) ?: return@withContext Sent.Unreachable
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
        _usage.value = emptyList()
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

    // ---- HTTP ----

    private class HttpResult(val code: Int, val body: String, val authenticate: String?)

    /** Null when the bridge could not be reached. */
    private suspend fun send(request: Request): HttpResult? = suspendCancellableCoroutine { cont ->
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
