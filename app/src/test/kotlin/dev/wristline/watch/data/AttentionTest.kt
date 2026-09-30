package dev.wristline.watch.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AttentionTest {
    private val open = "claude-code:1"
    private val other = "codex:2"

    @Test
    fun notLookingAlwaysPosts() {
        for (done in listOf(false, true)) {
            for (openSession in listOf(null, open, other)) {
                assertEquals(Attention.POST, attentionFor(looking = false, done = done, sessionId = open, openSession = openSession))
            }
        }
    }

    @Test
    fun lookingTicksForRequestsAndNeedsInputWhereverTheUserIs() {
        for (openSession in listOf(null, open, other)) {
            assertEquals(Attention.TICK, attentionFor(looking = true, done = false, sessionId = open, openSession = openSession))
        }
    }

    @Test
    fun lookingTicksForADoneAlertOfTheOpenSessionOnly() {
        assertEquals(Attention.TICK, attentionFor(looking = true, done = true, sessionId = open, openSession = open))
        assertEquals(Attention.POST, attentionFor(looking = true, done = true, sessionId = open, openSession = other))
        assertEquals(Attention.POST, attentionFor(looking = true, done = true, sessionId = open, openSession = null))
    }
}
