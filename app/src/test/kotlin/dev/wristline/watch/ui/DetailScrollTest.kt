package dev.wristline.watch.ui

import androidx.compose.ui.unit.dp
import dev.wristline.watch.data.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailScrollTest {
    @Test
    fun sideActionsRiseToStayInsideTheRoundScreen() {
        // The smallest round screen: the side buttons' centers 56dp out need about 21dp more.
        assertEquals(20.75f, sideActionLift(192.dp, round = true).value, 0.1f)
        // Galaxy Watch at 480px (295dp): a few dp.
        assertEquals(7.4f, sideActionLift(295.dp, round = true).value, 0.1f)
        assertEquals(0f, sideActionLift(192.dp, round = false).value, 0f)
    }

    @Test
    fun enteringJumpsToTheNewestWhereverTheListStands() {
        // A restored or held position away from the end does not keep the list there.
        assertEquals(EndScroll.JUMP, endScroll(hasItems = true, placed = false, atEnd = false))
        assertEquals(EndScroll.JUMP, endScroll(hasItems = true, placed = false, atEnd = true))
    }

    @Test
    fun nothingToPlaceWithoutItems() {
        assertEquals(EndScroll.NONE, endScroll(hasItems = false, placed = false, atEnd = true))
        assertEquals(EndScroll.NONE, endScroll(hasItems = false, placed = true, atEnd = false))
    }

    @Test
    fun onceBackAtTheNewestItFollowsOnlyFromTheEnd() {
        assertEquals(EndScroll.FOLLOW, endScroll(hasItems = true, placed = true, atEnd = true))
        // The user scrolled back in this visit: new items leave the list where it is.
        assertEquals(EndScroll.NONE, endScroll(hasItems = true, placed = true, atEnd = false))
    }

    @Test
    fun comingBackInTheSameVisitStaysWhereItWasLeft() {
        // Back from the quick replies without sending: neither jumped nor followed, even at the end.
        assertEquals(EndScroll.NONE, endScroll(hasItems = true, placed = true, atEnd = false, returned = true))
        assertEquals(EndScroll.NONE, endScroll(hasItems = true, placed = true, atEnd = true, returned = true))
        // Back after sending (a new visit): to the newest.
        assertEquals(EndScroll.JUMP, endScroll(hasItems = true, placed = false, atEnd = false, returned = false))
    }

    @Test
    fun jumpButtonShowsOnlyScrolledBack() {
        assertTrue(showJumpToLatest(atEnd = false, loading = false, confirming = false))
        assertFalse(showJumpToLatest(atEnd = true, loading = false, confirming = false))
    }

    @Test
    fun jumpButtonHidesWhileLoadingOrConfirming() {
        assertFalse(showJumpToLatest(atEnd = false, loading = true, confirming = false))
        assertFalse(showJumpToLatest(atEnd = false, loading = false, confirming = true))
    }

    @Test
    fun suggestionShowsOnlyWhileIdleAndFree() {
        assertEquals("Run the tests", ghostSuggestion(SessionStatus.IDLE, hasRequest = false, suggestion = "  Run the tests\n", promptBlock = null))
        assertNull(ghostSuggestion(SessionStatus.RUNNING, hasRequest = false, suggestion = "Run the tests", promptBlock = null))
        assertNull(ghostSuggestion(SessionStatus.NEEDS_INPUT, hasRequest = false, suggestion = "Run the tests", promptBlock = null))
        assertNull(ghostSuggestion(SessionStatus.ENDED, hasRequest = false, suggestion = "Run the tests", promptBlock = "not_live"))
        assertNull(ghostSuggestion(null, hasRequest = false, suggestion = "Run the tests", promptBlock = null))
        assertNull(ghostSuggestion(SessionStatus.IDLE, hasRequest = true, suggestion = "Run the tests", promptBlock = null))
        assertNull(ghostSuggestion(SessionStatus.IDLE, hasRequest = false, suggestion = "Run the tests", promptBlock = "busy"))
        assertNull(ghostSuggestion(SessionStatus.IDLE, hasRequest = false, suggestion = null, promptBlock = null))
        assertNull(ghostSuggestion(SessionStatus.IDLE, hasRequest = false, suggestion = " \n", promptBlock = null))
    }

    @Test
    fun footersKeyChangesWhenTheSpinnerGivesWayToTheSuggestion() {
        // The same number of entries under the newest item, but a different one: the list follows.
        assertNotEquals(
            footersKey(working = true, block = false, outcome = false, ghost = false),
            footersKey(working = false, block = false, outcome = false, ghost = true),
        )
        assertNotEquals(
            footersKey(working = false, block = false, outcome = true, ghost = false),
            footersKey(working = false, block = false, outcome = false, ghost = true),
        )
        assertEquals(0, footersKey(working = false, block = false, outcome = false, ghost = false))
    }
}
