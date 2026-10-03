package dev.wristline.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailScrollTest {
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
