package dev.wristline.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NotifierTest {
    // Pinned values: a notification posted by one process must be cancellable by the next, so the
    // id of a given request or session id may never change.
    @Test
    fun idsAreStableAcrossProcesses() {
        assertEquals(108400324, notificationId("req-3"))
        assertEquals(-761102199, notificationId("claude-code:6f1c2d3e-0000-4000-8000-000000000001"))
        assertEquals(-494130475, notificationId("codex:019a2b3c-4d5e-7f60-8a9b-0c1d2e3f4a5b"))
        assertEquals(1582880, notificationId("세션"))
    }

    @Test
    fun idsDependOnlyOnTheKey() {
        val key = "req-" + 42
        assertEquals(notificationId("req-42"), notificationId(key))
        assertNotEquals(notificationId("req-1"), notificationId("req-2"))
    }
}
