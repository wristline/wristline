package dev.wristline.watch.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PostedRequestsTest {
    private fun request(id: String) = PendingRequest(id = id, sessionId = "claude-code:1", kind = "question", createdAt = "2026-01-01T00:00:00Z")

    private val posted = PostedRequests()
    private val notified = mutableListOf<String>()

    /** What going to the background does: posts the requests not posted yet. */
    private fun toBackground(requests: List<PendingRequest>, shows: Boolean = true) {
        posted.unposted(requests).forEach { request -> posted.post(request) { notified += it.id; shows } }
    }

    @Test
    fun goingToTheBackgroundTwicePostsOnce() {
        val requests = listOf(request("a"), request("b"))
        toBackground(requests)
        toBackground(requests)
        assertEquals(listOf("a", "b"), notified)
    }

    @Test
    fun onlyTheRequestsNotPostedYet() {
        // "a" was posted when it arrived; "b" was only ticked on screen.
        posted.post(request("a")) { true }
        toBackground(listOf(request("a"), request("b")))
        assertEquals(listOf("b"), notified)
    }

    @Test
    fun aNotificationThatWasNotShownIsTriedAgain() {
        // Notifications off, or the channel muted: Notifier.request returns false.
        toBackground(listOf(request("a")), shows = false)
        toBackground(listOf(request("a")))
        assertEquals(listOf("a", "a"), notified)
    }

    @Test
    fun aSnapshotWithoutTheRequestDropsIt() {
        toBackground(listOf(request("a"), request("b")))
        posted.keepOnly(listOf(request("b")))
        notified.clear()
        // Same id again (e.g. a new request after a bridge restart): posted anew; "b" stays quiet.
        toBackground(listOf(request("a"), request("b")))
        assertEquals(listOf("a"), notified)
    }

    @Test
    fun aResolvedRequestIsForgotten() {
        toBackground(listOf(request("a")))
        posted.forget("a")
        toBackground(listOf(request("a")))
        assertEquals(listOf("a", "a"), notified)
    }

    @Test
    fun clearForgetsEverything() {
        toBackground(listOf(request("a")))
        posted.clear()
        toBackground(listOf(request("a")))
        assertEquals(listOf("a", "a"), notified)
    }
}
