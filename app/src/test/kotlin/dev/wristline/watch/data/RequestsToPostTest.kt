package dev.wristline.watch.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RequestsToPostTest {
    private fun request(id: String) = PendingRequest(id = id, sessionId = "claude-code:1", kind = "question", createdAt = "2026-01-01T00:00:00Z")

    @Test
    fun postsOnlyTheRequestsNotPostedYet() {
        val shown = request("a")
        val ticked = request("b")
        assertEquals(listOf(ticked), requestsToPost(listOf(shown, ticked), posted = setOf("a")))
    }

    @Test
    fun nothingWhenEveryRequestWasPosted() {
        // A dismissed notification is still counted as posted: going to the background again stays quiet.
        assertEquals(emptyList<PendingRequest>(), requestsToPost(listOf(request("a")), posted = setOf("a")))
    }

    @Test
    fun everythingWhenNothingWasPosted() {
        val requests = listOf(request("a"), request("b"))
        assertEquals(requests, requestsToPost(requests, posted = emptySet()))
    }
}
