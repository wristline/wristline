package dev.wristline.watch

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationTest {
    private val list = Route.Sessions
    private val session = Route.Session("claude-code:s1")
    private val request = Route.Request("req-7")

    @Test
    fun notificationTargetOpensARequestOverItsSession() {
        assertEquals(listOf(session, request), notificationTarget(requestId = "req-7", sessionId = "claude-code:s1"))
        assertEquals(listOf(request), notificationTarget(requestId = "req-7", sessionId = null))
        assertEquals(listOf(session), notificationTarget(requestId = null, sessionId = "claude-code:s1"))
        assertNull(notificationTarget(requestId = null, sessionId = null))
    }

    @Test
    fun notificationStackPutsTheTargetAboveTheList() {
        val target = listOf(session, request)
        assertEquals(listOf(list, session, request), notificationStack(listOf(list), target))
        // A session or request on top gives way, as the old single-top navigation did.
        assertEquals(listOf(list, session, request), notificationStack(listOf(list, Route.Session("other")), target))
        assertEquals(listOf(list, session, request), notificationStack(listOf(list, session, Route.Request("req-1")), target))
        assertEquals(listOf(list, session), notificationStack(listOf(list, request), listOf(session)))
    }

    @Test
    fun notificationStackKeepsAnAskScreenBelow() {
        // Popping the Ask screen would cancel its question (AskCanceller); a notification covers it instead.
        val ask = Route.Ask("ask-1")
        assertEquals(listOf(list, ask, session, request), notificationStack(listOf(list, ask), listOf(session, request)))
        assertEquals(listOf(list, Route.Settings, session), notificationStack(listOf(list, Route.Settings), listOf(session)))
    }

    @Test
    fun replaceWithKeepsTheSharedBottom() {
        val stack = mutableListOf<NavKey>(list, Route.Ask("a1"), request)
        stack.replaceWith(listOf(list, Route.Ask("a2"), request))
        assertEquals(listOf(list, Route.Ask("a2"), request), stack)
        stack.replaceWith(listOf(Route.Welcome))
        assertEquals(listOf(Route.Welcome), stack)
        stack.replaceWith(listOf(Route.Welcome, Route.Notify))
        assertEquals(listOf(Route.Welcome, Route.Notify), stack)
    }

    @Test
    fun everyKeyRoundTripsThroughTheSavedStateModule() {
        val json = Json { serializersModule = routeModule }
        val keys: List<NavKey> = listOf(
            Route.Welcome, Route.Notify, Route.Address, Route.Code("https://host.ts.net:8443"), Route.Sessions,
            session, request, Route.Usage, Route.Ask("ask-1"), Route.Asks, Route.Settings,
        )
        val serializer = ListSerializer(PolymorphicSerializer(NavKey::class))
        val encoded = json.encodeToString(serializer, keys)
        assertEquals(keys, json.decodeFromString(serializer, encoded))
    }
}
