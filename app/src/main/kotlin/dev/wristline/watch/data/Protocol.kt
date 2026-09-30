// Mirrors wristline-bridge protocol v1 (docs/protocol.md).
//
// Hand-written copy of wristline-bridge src/protocol.ts. Compatibility rules this side relies on:
// - unknown JSON keys are ignored, so the bridge may add fields without an app update;
// - unknown WebSocket event types are skipped ([decodeServerEvent] returns null);
// - string unions (provider, status, kind, error codes) stay [String] so a new value, such as a
//   new provider, still decodes;
// - a snapshot whose apiVersion is not [API_VERSION] means the app must show Incompatible.
// ProtocolTest decodes every fixture in assets/protocol/ (copied by scripts/sync-protocol.sh).
package dev.wristline.watch.data

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

const val API_VERSION = 1

/** A permission request carries exactly one question with this id. */
const val PERMISSION_QUESTION = "decision"

/** WebSocket close code the bridge sends when this device's token is revoked. */
const val CLOSE_REVOKED = 4001

val WireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

object ProviderId {
    const val CLAUDE_CODE = "claude-code"
    const val CODEX = "codex"
}

object SessionStatus {
    const val RUNNING = "running"
    const val IDLE = "idle"
    const val NEEDS_INPUT = "needs_input"
    const val ENDED = "ended"
}

object ItemKind {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val TOOL = "tool"
    const val NOTICE = "notice"
}

object RequestKind {
    const val PERMISSION = "permission"
    const val QUESTION = "question"
}

/** Option ids of the permission question. */
object Decision {
    const val ALLOW = "allow"
    const val ALWAYS = "always"
    const val DENY = "deny"
    const val DEFER = "defer"
}

/** Values of [ServerEvent.Alert.alert]. */
object AlertKind {
    const val NEEDS_INPUT = "needs_input"
    const val DONE = "done"
}

object AskStatus {
    const val RUNNING = "running"
    const val DONE = "done"
    const val ERROR = "error"
}

@Immutable
@Serializable
data class ContextUsage(val used: Long, val window: Long)

/** Absent on a session or usage entry means a single or unknown account. */
@Immutable
@Serializable
data class Account(
    val id: String,
    /** Never empty; the bridge picks a user label, email, organization or id prefix. */
    val label: String,
    /** Claude Code only: attributed from the home's login timeline rather than known exactly. */
    val estimated: Boolean = false,
)

@Immutable
@Serializable
data class Session(
    /** `<provider>:<nativeId>` */
    val id: String,
    val provider: String,
    /** May be empty when the agent has not produced a title yet. */
    val title: String = "",
    val cwd: String = "",
    val status: String,
    /** ISO 8601 */
    val lastActivity: String,
    /** Present when a prompt would be refused; one of the PromptBlock codes. */
    val promptBlock: String? = null,
    val context: ContextUsage? = null,
    val account: Account? = null,
    /** Display name of the model, e.g. `Fable 5.1`; absent when unknown. */
    val model: String? = null,
    /** Reasoning effort, e.g. `xhigh`; absent when unknown or not applicable. */
    val effort: String? = null,
)

@Immutable
@Serializable
data class Item(
    /** Position in the session, starting at 1. An updated item is re-sent with the same seq. */
    val seq: Long,
    val kind: String,
    val ts: String,
    val text: String = "",
    val detail: String? = null,
    val pending: Boolean = false,
    val error: Boolean = false,
)

@Immutable
@Serializable
data class Option(val id: String, val label: String, val description: String? = null)

@Immutable
@Serializable
data class Question(
    val id: String,
    val header: String? = null,
    val text: String = "",
    val multi: Boolean = false,
    val options: List<Option> = emptyList(),
)

@Immutable
@Serializable
data class PendingRequest(
    val id: String,
    val sessionId: String,
    val kind: String,
    val title: String = "",
    val questions: List<Question> = emptyList(),
    val createdAt: String,
)

/** Question id -> selected option ids. */
typealias Answers = Map<String, List<String>>

@Immutable
@Serializable
data class UsageWindow(
    /** `5h` | `7d` (claude-code), `primary` | `secondary` (codex) */
    val id: String,
    val usedPercent: Double,
    val resetsAt: String? = null,
    val minutes: Int? = null,
    /** Name for people (`5h`, `7d Opus`); absent for Codex and older bridges. */
    val label: String? = null,
)

@Immutable
@Serializable
data class Usage(
    val provider: String,
    val updatedAt: String,
    val windows: List<UsageWindow> = emptyList(),
    val account: Account? = null,
)

/** Identity of a usage entry: a `usage` event replaces the entry with the same key. */
val Usage.key: String get() = provider + ":" + (account?.id ?: "")

/** A Quick Ask: one headless question to `claude -p` or `codex exec` on the PC, kept per device. */
@Immutable
@Serializable
data class Ask(
    val id: String,
    val provider: String,
    /** The text the watch sent; empty until the next `GET /api/asks` when only an event was seen. */
    val question: String = "",
    val status: String,
    /** Present when done. */
    val answer: String? = null,
    /** Display name of the model when done, e.g. `Haiku 4.5`; absent for Codex without a configured model. */
    val model: String? = null,
    val durationMs: Long? = null,
    /** When failed: `timeout` | `cancelled` | `exit_<code>` | `bad_output` | a short CLI message. */
    val error: String? = null,
    val createdAt: String,
)

// REST bodies.

@Serializable
data class BridgeInfo(val name: String, val version: String, val apiVersion: Int)

@Serializable
data class ProviderHealth(val id: String, val status: String, val version: String? = null)

@Serializable
data class Health(
    val name: String,
    val version: String,
    val apiVersion: Int,
    val providers: List<ProviderHealth> = emptyList(),
)

@Serializable
data class PairRequest(val code: String, val deviceName: String)

@Serializable
data class PairResponse(val token: String, val deviceId: String, val bridge: BridgeInfo)

@Serializable
data class SessionList(val sessions: List<Session>)

@Serializable
data class ItemPage(val items: List<Item>, val hasMore: Boolean = false)

@Serializable
data class PromptBody(val text: String)

@Serializable
data class RequestList(val requests: List<PendingRequest>)

@Serializable
data class AnswerBody(val answers: Answers)

@Serializable
data class UsageList(val usage: List<Usage>)

@Serializable
data class AskBody(val provider: String, val text: String, val model: String? = null)

@Serializable
data class AskAccepted(val askId: String)

@Serializable
data class AskList(val asks: List<Ask>)

/** Error body of a 4xx response; [error] is an ErrorCode or a PromptBlock code. */
@Serializable
data class ApiError(val error: String)

/**
 * Events received over `/api/ws`. Deliberately not a kotlinx polymorphic hierarchy: polymorphic
 * decoding throws on an unknown `type`, while the protocol requires skipping it.
 */
sealed interface ServerEvent {
    /** [alerts] are the bridge's recent alerts, oldest first, for the ones a closed socket missed. */
    @Serializable
    data class Snapshot(
        val apiVersion: Int,
        val sessions: List<Session> = emptyList(),
        val requests: List<PendingRequest> = emptyList(),
        val usage: List<Usage> = emptyList(),
        val alerts: List<Alert> = emptyList(),
    ) : ServerEvent

    @Serializable
    data class SessionChanged(val session: Session) : ServerEvent

    @Serializable
    data class SessionRemoved(val sessionId: String) : ServerEvent

    @Serializable
    data class ItemChanged(val sessionId: String, val item: Item) : ServerEvent

    @Serializable
    data class RequestAdded(val request: PendingRequest) : ServerEvent

    /** [by] is `watch` | `terminal` | `timeout`. */
    @Serializable
    data class Resolved(val requestId: String, val by: String) : ServerEvent

    @Serializable
    data class UsageChanged(val usage: Usage) : ServerEvent

    /**
     * [alert] is `needs_input` | `done`. [title], when present, heads a done notification. [id]
     * and [at] (ISO 8601) come with bridges that replay alerts in the snapshot: the id tells a
     * replayed alert from one already handled, the time keeps old ones from being replayed.
     */
    @Serializable
    data class Alert(
        val sessionId: String,
        val alert: String,
        val text: String? = null,
        val title: String? = null,
        val id: String? = null,
        val at: String? = null,
    ) : ServerEvent

    /** Sent only to the device that asked. [text] is the answer, present when [status] is done. */
    @Serializable
    data class AskChanged(
        val askId: String,
        val provider: String,
        val status: String,
        val text: String? = null,
        val model: String? = null,
        val durationMs: Long? = null,
        val error: String? = null,
    ) : ServerEvent
}

/**
 * Decodes one WebSocket message. Returns null when [obj] has no string `type` or the type is
 * unknown. Throws [kotlinx.serialization.SerializationException] or [IllegalArgumentException]
 * when a known event is malformed.
 *
 * A snapshot with an unsupported apiVersion is returned with only its [ServerEvent.Snapshot.apiVersion]
 * set, because the rest of its shape may have changed.
 */
fun decodeServerEvent(obj: JsonObject): ServerEvent? {
    val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return when (type) {
        "snapshot" -> {
            val version = (obj["apiVersion"] as? JsonPrimitive)?.intOrNull ?: 0
            if (version != API_VERSION) {
                ServerEvent.Snapshot(apiVersion = version)
            } else {
                WireJson.decodeFromJsonElement(ServerEvent.Snapshot.serializer(), obj)
            }
        }
        "session" -> WireJson.decodeFromJsonElement(ServerEvent.SessionChanged.serializer(), obj)
        "session_removed" -> WireJson.decodeFromJsonElement(ServerEvent.SessionRemoved.serializer(), obj)
        "item" -> WireJson.decodeFromJsonElement(ServerEvent.ItemChanged.serializer(), obj)
        "request" -> WireJson.decodeFromJsonElement(ServerEvent.RequestAdded.serializer(), obj)
        "resolved" -> WireJson.decodeFromJsonElement(ServerEvent.Resolved.serializer(), obj)
        "usage" -> WireJson.decodeFromJsonElement(ServerEvent.UsageChanged.serializer(), obj)
        "alert" -> WireJson.decodeFromJsonElement(ServerEvent.Alert.serializer(), obj)
        "ask" -> WireJson.decodeFromJsonElement(ServerEvent.AskChanged.serializer(), obj)
        else -> null
    }
}

/** See [decodeServerEvent]; also returns null when [text] is JSON but not an object. */
fun parseServerEvent(text: String): ServerEvent? =
    (WireJson.parseToJsonElement(text) as? JsonObject)?.let(::decodeServerEvent)

/**
 * `{type:'subscribe', sessionId, kinds?}`; built by hand so a null sessionId is sent as JSON null.
 * Without [kinds] every item kind is sent.
 */
fun subscribeMessage(sessionId: String?, kinds: List<String>? = null): String =
    buildJsonObject {
        put("type", "subscribe")
        put("sessionId", sessionId)
        if (kinds != null) putJsonArray("kinds") { kinds.forEach { add(it) } }
    }.toString()

/** Epoch millis of an ISO 8601 timestamp, or null when it does not parse. */
fun isoToMillis(iso: String?): Long? {
    if (iso.isNullOrEmpty()) return null
    return try {
        Instant.parse(iso).toEpochMilli()
    } catch (_: DateTimeParseException) {
        try {
            OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
