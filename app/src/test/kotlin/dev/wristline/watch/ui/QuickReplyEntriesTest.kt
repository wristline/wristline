package dev.wristline.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickReplyEntriesTest {
    private val replies = listOf("Continue", "Run the tests again", "Commit and push")

    @Test
    fun theSuggestionComesFirstMarkedAboveTheReplies() {
        assertEquals(
            listOf(QuickReply("Explain the failure", suggested = true), QuickReply("Continue"), QuickReply("Commit and push")),
            quickReplyEntries("Explain the failure", listOf("Continue", "Commit and push")),
        )
    }

    @Test
    fun noOrBlankSuggestionLeavesTheReplies() {
        val plain = replies.map { QuickReply(it) }
        assertEquals(plain, quickReplyEntries(null, replies))
        assertEquals(plain, quickReplyEntries("", replies))
        assertEquals(plain, quickReplyEntries("  \n ", replies))
    }

    @Test
    fun aReplyIdenticalToTheSuggestionIsShownOnceAsTheSuggestion() {
        assertEquals(
            listOf(QuickReply("Run the tests again", suggested = true), QuickReply("Continue"), QuickReply("Commit and push")),
            quickReplyEntries("Run the tests again", replies),
        )
        // Trimmed before the comparison; a reply that merely differs in case stays.
        assertEquals(
            listOf(QuickReply("Continue", suggested = true), QuickReply("continue")),
            quickReplyEntries(" Continue\n", listOf("Continue", "continue")),
        )
    }

    @Test
    fun aSuggestionWithNoRepliesIsAlone() {
        assertEquals(listOf(QuickReply("Run the tests again", suggested = true)), quickReplyEntries("Run the tests again", emptyList()))
        assertEquals(emptyList<QuickReply>(), quickReplyEntries(null, emptyList()))
    }
}
