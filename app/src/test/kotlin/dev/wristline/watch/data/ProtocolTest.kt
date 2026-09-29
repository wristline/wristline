package dev.wristline.watch.data

import java.io.File
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
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
    fun isoTimestampsParse() {
        assertEquals(1790686680000L, isoToMillis("2026-09-29T12:58:00.000Z"))
        assertEquals(1790686680000L, isoToMillis("2026-09-29T21:58:00+09:00"))
        assertNull(isoToMillis("yesterday"))
        assertNull(isoToMillis(null))
    }
}
