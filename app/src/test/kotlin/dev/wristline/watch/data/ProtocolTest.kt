package dev.wristline.watch.data

import java.io.File
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
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
            ServerEvent.AskChanged::class.java,
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
                if (type == "subscribe" || type == "mode") return // ClientEvent: sent by the watch, never decoded
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
            "asks" in obj -> decode<AskList>(obj)
            "answers" in obj -> decode<AnswerBody>(obj)
            "code" in obj && "deviceName" in obj -> decode<PairRequest>(obj)
            "error" in obj -> decode<ApiError>(obj)
            "askId" in obj && obj.size == 1 -> decode<AskAccepted>(obj)
            // Before PromptBody: an ask body also carries `text`.
            "provider" in obj && "text" in obj -> decode<AskBody>(obj)
            "text" in obj && obj.size == 1 -> decode<PromptBody>(obj)
            else -> fail("unrecognised fixture shape with keys ${obj.keys}")
        }
    }

    private inline fun <reified T> decode(obj: JsonObject): T = WireJson.decodeFromJsonElement(obj)

    @Test
    fun planItemDecodesItsMarkAndOtherItemsDefaultToNone() {
        val plan = parseServerEvent(File(dir, "event-item-plan.json").readText()) as ServerEvent.ItemChanged
        assertEquals(ItemKind.ASSISTANT, plan.item.kind)
        assertTrue(plan.item.plan)
        val other = parseServerEvent(File(dir, "event-item.json").readText()) as ServerEvent.ItemChanged
        assertFalse(other.item.plan)
    }

    @Test
    fun limitItemAndAlertCarryTheResetTime() {
        val item = (parseServerEvent(File(dir, "event-item-limit.json").readText()) as ServerEvent.ItemChanged).item
        assertEquals(ItemKind.ASSISTANT, item.kind)
        assertTrue(item.error)
        assertEquals("2026-09-29T10:40:00.000Z", item.resetsAt)
        assertEquals(LimitKind.WINDOW, item.limitKind)
        assertFalse(item.resetsEstimated)
        val alert = parseServerEvent(File(dir, "event-alert-limit.json").readText()) as ServerEvent.Alert
        assertEquals(AlertKind.LIMIT, alert.alert)
        assertEquals("2026-09-29T10:40:00.000Z", alert.resetsAt)
        assertEquals(LimitKind.WINDOW, alert.limitKind)
        assertNull((parseServerEvent(File(dir, "event-alert.json").readText()) as ServerEvent.Alert).resetsAt)
        // Usage credits ran out: no reset time.
        val credits = (parseServerEvent(File(dir, "event-item-limit-credits.json").readText()) as ServerEvent.ItemChanged).item
        assertEquals(LimitKind.CREDITS, credits.limitKind)
        assertNull(credits.resetsAt)
        assertNull((parseServerEvent(File(dir, "event-item.json").readText()) as ServerEvent.ItemChanged).item.limitKind)
    }

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

    // Hand-written on purpose: `title` on a done alert is optional and newer than the fixture.
    @Test
    fun alertDecodesWithAndWithoutTitle() {
        val plain = parseServerEvent("""{"type":"alert","sessionId":"codex:1","alert":"done","text":"Finished."}""") as ServerEvent.Alert
        assertNull(plain.title)
        val titled = parseServerEvent(
            """{"type":"alert","sessionId":"codex:1","alert":"done","title":"Tests fixed","text":"All 12 pass."}""",
        ) as ServerEvent.Alert
        assertEquals("Tests fixed", titled.title)
        assertEquals("All 12 pass.", titled.text)
    }

    // Hand-written on purpose: `id` and `at` on alerts, and the snapshot's `alerts`, are newer than the fixtures.
    @Test
    fun alertIdAndTimeAreOptional() {
        val replayed = parseServerEvent(
            """{"type":"alert","id":"al-7","at":"2026-09-29T12:58:00Z","sessionId":"codex:1","alert":"needs_input","text":"Pick one"}""",
        ) as ServerEvent.Alert
        assertEquals("al-7", replayed.id)
        assertEquals("2026-09-29T12:58:00Z", replayed.at)
        val live = parseServerEvent("""{"type":"alert","sessionId":"codex:1","alert":"done"}""") as ServerEvent.Alert
        assertNull(live.id)
        assertNull(live.at)
    }

    @Test
    fun snapshotAlertsDecodeOldestFirstAndDefaultToNone() {
        val snapshot = parseServerEvent(
            """{"type":"snapshot","apiVersion":1,"sessions":[],"requests":[],"usage":[],"alerts":[
               {"id":"al-1","at":"2026-09-29T12:50:00Z","sessionId":"codex:1","alert":"done","title":"Tests fixed","text":"All pass."},
               {"id":"al-2","at":"2026-09-29T12:58:00Z","sessionId":"claude-code:2","alert":"needs_input"}]}""",
        ) as ServerEvent.Snapshot
        assertEquals(listOf("al-1", "al-2"), snapshot.alerts.map { it.id })
        assertEquals("Tests fixed", snapshot.alerts[0].title)
        assertEquals(AlertKind.NEEDS_INPUT, snapshot.alerts[1].alert)
        assertNull(snapshot.alerts[1].text)
        val without = parseServerEvent("""{"type":"snapshot","apiVersion":1}""") as ServerEvent.Snapshot
        assertTrue(without.alerts.isEmpty())
    }

    // Hand-written on purpose: the fixtures carry `account` on every live session and usage entry,
    // so the single-account (absent) case and an unknown key inside it are covered here.
    @Test
    fun sessionAndUsageDecodeWithAndWithoutAccount() {
        val list = decode<SessionList>(
            WireJson.parseToJsonElement(
                """{"sessions":[
                   {"id":"claude-code:1","provider":"claude-code","status":"running","lastActivity":"2026-09-29T00:00:00Z",
                    "account":{"id":"acc-a","label":"me@gmail.com","plan":"max","primary":true}},
                   {"id":"claude-code:2","provider":"claude-code","status":"idle","lastActivity":"2026-09-29T00:00:00Z",
                    "account":{"id":"acc-b","label":"school","estimated":true}},
                   {"id":"codex:3","provider":"codex","status":"idle","lastActivity":"2026-09-29T00:00:00Z"}]}""",
            ) as JsonObject,
        ).sessions
        assertEquals(Account("acc-a", "me@gmail.com", primary = true), list[0].account)
        assertFalse(list[0].account!!.estimated)
        assertEquals(Account("acc-b", "school", estimated = true), list[1].account)
        assertFalse(list[1].account!!.primary)
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

    // Hand-written on purpose: the fixtures carry a cancelled error and a done answer with a model;
    // a CLI message as the error and a Codex answer without a model are covered here.
    @Test
    fun askEventDecodesErrorAndDoneWithoutModel() {
        val failed = parseServerEvent(
            """{"type":"ask","askId":"ask-1","provider":"codex","status":"error","durationMs":312,"error":"exit_1"}""",
        ) as ServerEvent.AskChanged
        assertEquals("exit_1", failed.error)
        assertEquals(312L, failed.durationMs)
        assertNull(failed.text)
        val done = parseServerEvent(
            """{"type":"ask","askId":"ask-2","provider":"codex","status":"done","text":"OK","durationMs":2000}""",
        ) as ServerEvent.AskChanged
        assertEquals("OK", done.text)
        assertNull(done.model)
        assertNull(done.error)
        val list = decode<AskList>(WireJson.parseToJsonElement(File(dir, "asks.json").readText()) as JsonObject).asks
        assertEquals(AskStatus.ERROR, list[0].status)
        assertEquals("cancelled", list[0].error)
        assertEquals("Haiku 4.5", list[1].model)
        assertEquals("OK", list[1].answer)
    }

    // The fixtures carry `threadId` on every ask; an older bridge's ask without one is its own thread.
    @Test
    fun askThreadIdIsOptionalAndSent() {
        val list = decode<AskList>(WireJson.parseToJsonElement(File(dir, "asks.json").readText()) as JsonObject).asks
        val follow = list.first { it.threadId != it.id }
        assertEquals(follow.threadId, list.first { it.id == follow.threadId }.thread)
        val bare = decode<Ask>(
            WireJson.parseToJsonElement(
                """{"id":"ask-1","provider":"codex","question":"what?","status":"done","answer":"this","createdAt":"2026-09-29T00:00:00Z"}""",
            ) as JsonObject,
        )
        assertEquals("", bare.threadId)
        assertEquals("ask-1", bare.thread)
        val fixture = WireJson.parseToJsonElement(File(dir, "ask-thread.json").readText()) as JsonObject
        val body = AskBody(
            fixture.getValue("provider").jsonPrimitive.content,
            fixture.getValue("text").jsonPrimitive.content,
            threadId = fixture.getValue("threadId").jsonPrimitive.content,
        )
        assertEquals(fixture, WireJson.parseToJsonElement(WireJson.encodeToString(AskBody.serializer(), body)))
        assertEquals("""{"provider":"codex","text":"what?"}""", WireJson.encodeToString(AskBody.serializer(), AskBody("codex", "what?")))
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

    // Not in the fixtures yet (synced from the bridge later): both optional.
    @Test
    fun sessionTurnStartAndProgressDecodeWhenPresent() {
        val list = decode<SessionList>(
            WireJson.parseToJsonElement(
                """{"sessions":[
                   {"id":"claude-code:1","provider":"claude-code","status":"running","lastActivity":"2026-10-02T00:00:00Z",
                    "turnStartedAt":"2026-10-02T00:00:00Z","progress":{"done":3,"total":7,"future":true}},
                   {"id":"codex:2","provider":"codex","status":"idle","lastActivity":"2026-10-02T00:00:00Z"},
                   {"id":"claude-code:3","provider":"claude-code","status":"running","lastActivity":"2026-10-02T00:00:00Z",
                    "progress":{"done":1,"total":4,"current":"Run the tests"}},
                   {"id":"claude-code:4","provider":"claude-code","status":"running","lastActivity":"2026-10-02T00:00:00Z",
                    "progress":{"done":1,"total":2,"kind":"agents"}}]}""",
            ) as JsonObject,
        ).sessions
        assertEquals("2026-10-02T00:00:00Z", list[0].turnStartedAt)
        assertEquals(TaskProgress(3, 7), list[0].progress)
        assertNull(list[1].turnStartedAt)
        assertNull(list[1].progress)
        assertEquals(TaskProgress(1, 4, current = "Run the tests"), list[2].progress)
        assertFalse(list[2].progress!!.agents)
        assertEquals(TaskProgress(1, 2, kind = PROGRESS_AGENTS), list[3].progress)
        assertTrue(list[3].progress!!.agents)
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
    fun modeMessageMatchesTheFixture() {
        val fixture = WireJson.parseToJsonElement(File(dir, "client-mode.json").readText())
        assertEquals(fixture, WireJson.parseToJsonElement(modeMessage(background = true)))
        assertEquals("""{"type":"mode","mode":"foreground"}""", modeMessage(background = false))
    }

    @Test
    fun subscribeWithKindsMatchesTheFixture() {
        val fixture = WireJson.parseToJsonElement(File(dir, "client-subscribe-kinds.json").readText()) as JsonObject
        val sessionId = fixture.getValue("sessionId").jsonPrimitive.content
        val kinds = fixture.getValue("kinds").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(fixture, WireJson.parseToJsonElement(subscribeMessage(sessionId, kinds)))
        // The app asks for a subset of the kinds the bridge's example names.
        assertTrue(kinds.containsAll(CONVERSATION_KINDS))
    }

    @Test
    fun isoTimestampsParse() {
        assertEquals(1790686680000L, isoToMillis("2026-09-29T12:58:00.000Z"))
        assertEquals(1790686680000L, isoToMillis("2026-09-29T21:58:00+09:00"))
        assertNull(isoToMillis("yesterday"))
        assertNull(isoToMillis(null))
    }
}
