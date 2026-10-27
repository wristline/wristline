package dev.wristline.watch.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
