package dev.wristline.watch.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOnRefreshTest {
    private val now = 1_000_000L

    @Test
    fun fetchesInTheBackgroundWhenTheListIsOldOrNeverCame() {
        assertTrue(screenOnRefreshDue(background = true, fetchedAt = null, now = now))
        assertTrue(screenOnRefreshDue(background = true, fetchedAt = now - SCREEN_ON_REFRESH_MS, now = now))
        assertTrue(screenOnRefreshDue(background = true, fetchedAt = now - 60_000, now = now))
    }

    @Test
    fun skipsAListFetchedLessThan20SecondsAgo() {
        assertFalse(screenOnRefreshDue(background = true, fetchedAt = now - SCREEN_ON_REFRESH_MS + 1, now = now))
        assertFalse(screenOnRefreshDue(background = true, fetchedAt = now, now = now))
    }

    @Test
    fun skipsInTheForegroundWhereEveryChangeIsPushed() {
        assertFalse(screenOnRefreshDue(background = false, fetchedAt = null, now = now))
        assertFalse(screenOnRefreshDue(background = false, fetchedAt = now - 60_000, now = now))
    }
}
