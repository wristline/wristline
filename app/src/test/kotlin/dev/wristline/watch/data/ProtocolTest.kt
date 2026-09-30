package dev.wristline.watch.data

import java.io.File
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Contract test against the bridge's fixtures (app/src/main/assets/protocol, copied by
 * scripts/sync-protocol.sh). Every fixture must decode with the app's models; a fixture shape the
 * app does not recognise fails the test so protocol drift is noticed.
 */
class ProtocolTest {
    // Gradle runs unit tests with the module directory as the working directory.
    private val dir = File("src/main/assets/protocol")

    private fun fixtures(): List<File> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }

    @Test
    fun everyFixtureDecodes() {
        val files = fixtures()
        assertTrue("no fixtures in ${dir.absolutePath}", files.isNotEmpty())
        val eventTypes = mutableSetOf<Class<*>>()
        for (file in files) {
            try {
                decodeFixture(WireJson.parseToJsonElement(file.readText()), eventTypes)
            } catch (e: Exception) {
                throw AssertionError("${file.name}: ${e.message}", e)
            }
        }
        val known = setOf(
            ServerEvent.Snapshot::class.java,
            ServerEvent.SessionChanged::class.java,
            ServerEvent.SessionRemoved::class.java,
            ServerEvent.ItemChanged::class.java,
            ServerEvent.RequestAdded::class.java,
            ServerEvent.Resolved::class.java,
            ServerEvent.UsageChanged::class.java,
            ServerEvent.Alert::class.java,
        )
        assertEquals("fixtures should cover every event type", known, eventTypes)
    }

    private fun decodeFixture(element: JsonElement, eventTypes: MutableSet<Class<*>>) {
        when (element) {
            is JsonArray -> element.forEach { decodeFixture(it, eventTypes) }
            is JsonObject -> decodeObject(element, eventTypes)
            else -> fail("unexpected top-level JSON: $element")
        }
    }

    private fun decodeObject(obj: JsonObject, eventTypes: MutableSet<Class<*>>) {
        when {
            "type" in obj -> {
                val type = obj.getValue("type").jsonPrimitive.content
                if (type == "subscribe") return // ClientEvent: sent by the watch, never decoded
                val event = decodeServerEvent(obj)
                assertNotNull("unknown event type '$type'", event)
                if (event is ServerEvent.Snapshot) assertEquals(API_VERSION, event.apiVersion)
                eventTypes += event!!.javaClass
            }
            "token" in obj -> assertEquals(API_VERSION, decode<PairResponse>(obj).bridge.apiVersion)
            "providers" in obj -> decode<Health>(obj)
            "sessions" in obj -> decode<SessionList>(obj)
            "items" in obj -> decode<ItemPage>(obj)
            "requests" in obj -> decode<RequestList>(obj)
            "usage" in obj -> decode<UsageList>(obj)
            "answers" in obj -> decode<AnswerBody>(obj)
            "code" in obj && "deviceName" in obj -> decode<PairRequest>(obj)
            "error" in obj -> decode<ApiError>(obj)
            "text" in obj && obj.size == 1 -> decode<PromptBody>(obj)
            else -> fail("unrecognised fixture shape with keys ${obj.keys}")
        }
    }

    private inline fun <reified T> decode(obj: JsonObject): T = WireJson.decodeFromJsonElement(obj)

    @Test
    fun unknownEventTypeIsIgnored() {
        assertNull(parseServerEvent("""{"type":"future_event","payload":{"x":[1,2]}}"""))
        assertNull(parseServerEvent("""{"type":7}"""))
        assertNull(parseServerEvent("""{"sessionId":"codex:1"}"""))
        assertNull(parseServerEvent("""[{"type":"session"}]"""))
    }

    @Test
    fun unknownKeysAndNewEnumValuesDecode() {
        val event = parseServerEvent(
            """{"type":"session","extra":true,"session":{"id":"gemini-cli:1","provider":"gemini-cli",
               "title":"t","cwd":"/w","status":"paused","lastActivity":"2026-09-29T00:00:00Z",
               "promptBlock":"new_reason","newField":{"a":1}}}""",
        ) as ServerEvent.SessionChanged
        assertEquals("gemini-cli", event.session.provider)
        assertEquals("paused", event.session.status)
        assertNull(event.session.context)
    }

    // Hand-written on purpose: the fixtures carry `account` on every live session and usage entry,
    // so the single-account (absent) case and an unknown key inside it are covered here.
    @Test
    fun sessionAndUsageDecodeWithAndWithoutAccount() {
        val list = decode<SessionList>(
            WireJson.parseToJsonElement(
                """{"sessions":[
                   {"id":"claude-code:1","provider":"claude-code","status":"running","lastActivity":"2026-09-29T00:00:00Z",
                    "account":{"id":"acc-a","label":"me@gmail.com","plan":"max"}},
                   {"id":"claude-code:2","provider":"claude-code","status":"idle","lastActivity":"2026-09-29T00:00:00Z",
                    "account":{"id":"acc-b","label":"school","estimated":true}},
                   {"id":"codex:3","provider":"codex","status":"idle","lastActivity":"2026-09-29T00:00:00Z"}]}""",
            ) as JsonObject,
        ).sessions
        assertEquals(Account("acc-a", "me@gmail.com"), list[0].account)
        assertFalse(list[0].account!!.estimated)
        assertEquals(Account("acc-b", "school", estimated = true), list[1].account)
        assertNull(list[2].account)

        val usage = decode<UsageList>(
            WireJson.parseToJsonElement(
                """{"usage":[
                   {"provider":"claude-code","updatedAt":"2026-09-29T00:00:00Z","windows":[{"id":"5h","usedPercent":42}],
                    "account":{"id":"acc-a","label":"me@gmail.com"}},
                   {"provider":"claude-code","updatedAt":"2026-09-29T00:00:00Z","windows":[],
                    "account":{"id":"acc-b","label":"school","estimated":true}},
                   {"provider":"codex","updatedAt":"2026-09-29T00:00:00Z","windows":[{"id":"primary","usedPercent":13}]}]}""",
            ) as JsonObject,
        ).usage
        assertEquals(Account("acc-a", "me@gmail.com"), usage[0].account)
        assertTrue(usage[1].account!!.estimated)
        assertNull(usage[2].account)

        val event = parseServerEvent(
            """{"type":"usage","usage":{"provider":"codex","updatedAt":"2026-09-29T00:00:00Z","windows":[],
               "account":{"id":"chatgpt-1","label":"school"}}}""",
        ) as ServerEvent.UsageChanged
        assertEquals(Account("chatgpt-1", "school"), event.usage.account)
    }

    @Test
    fun usageWindowLabelIsOptional() {
        val usage = decode<UsageList>(WireJson.parseToJsonElement(File(dir, "usage.json").readText()) as JsonObject).usage
        val windows = usage.flatMap { it.windows }
        assertEquals("7d Fable", windows.first { it.id == "7d_fable" }.label)
        assertNull(windows.first { it.id == "primary" }.label)
    }

    // The fixtures carry model and effort together; one without the other, or neither, is covered here.
    @Test
    fun sessionModelAndEffortDecodeWhenPresent() {
        val list = decode<SessionList>(
            WireJson.parseToJsonElement(
                """{"sessions":[
                   {"id":"claude-code:1","provider":"claude-code","status":"running","lastActivity":"2026-09-29T00:00:00Z",
                    "model":"Fable 5.1","effort":"xhigh"},
                   {"id":"codex:2","provider":"codex","status":"idle","lastActivity":"2026-09-29T00:00:00Z","model":"gpt-5.3-codex"},
                   {"id":"codex:3","provider":"codex","status":"idle","lastActivity":"2026-09-29T00:00:00Z"}]}""",
            ) as JsonObject,
        ).sessions
        assertEquals("Fable 5.1", list[0].model)
        assertEquals("xhigh", list[0].effort)
        assertEquals("gpt-5.3-codex", list[1].model)
        assertNull(list[1].effort)
        assertNull(list[2].model)
        assertNull(list[2].effort)
    }

    @Test
    fun usageKeyIsProviderAndAccountId() {
        val at = "2026-09-29T00:00:00Z"
        assertEquals("claude-code:", Usage(ProviderId.CLAUDE_CODE, at).key)
        assertEquals("claude-code:acc-a", Usage(ProviderId.CLAUDE_CODE, at, account = Account("acc-a", "me")).key)
        assertEquals("codex:acc-a", Usage(ProviderId.CODEX, at, account = Account("acc-a", "me")).key)
        // The label and estimated flag do not take part in the identity.
        assertEquals(
            Usage(ProviderId.CODEX, at, account = Account("acc-a", "me")).key,
            Usage(ProviderId.CODEX, at, account = Account("acc-a", "other", estimated = true)).key,
        )
    }

    @Test
    fun snapshotWithOtherApiVersionKeepsOnlyTheVersion() {
        val event = parseServerEvent("""{"type":"snapshot","apiVersion":2,"sessions":"new shape"}""")
        assertEquals(ServerEvent.Snapshot(apiVersion = 2), event)
    }

    @Test
    fun malformedKnownEventThrows() {
        assertThrows(SerializationException::class.java) {
            parseServerEvent("""{"type":"item","sessionId":"codex:1"}""")
        }
    }

    @Test
    fun subscribeSendsExplicitNull() {
        assertEquals("""{"type":"subscribe","sessionId":null}""", subscribeMessage(null))
        assertEquals("""{"type":"subscribe","sessionId":"codex:1"}""", subscribeMessage("codex:1"))
    }

    @Test
    fun subscribeWithKindsMatchesTheFixture() {
        val fixture = WireJson.parseToJsonElement(File(dir, "client-subscribe-kinds.json").readText()) as JsonObject
        val sessionId = fixture.getValue("sessionId").jsonPrimitive.content
        assertEquals(fixture, WireJson.parseToJsonElement(subscribeMessage(sessionId, CONVERSATION_KINDS)))
    }

    @Test
    fun isoTimestampsParse() {
        assertEquals(1790686680000L, isoToMillis("2026-09-29T12:58:00.000Z"))
        assertEquals(1790686680000L, isoToMillis("2026-09-29T21:58:00+09:00"))
        assertNull(isoToMillis("yesterday"))
        assertNull(isoToMillis(null))
    }
}
