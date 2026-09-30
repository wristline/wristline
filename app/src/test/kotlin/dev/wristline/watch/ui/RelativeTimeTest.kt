package dev.wristline.watch.ui

import dev.wristline.watch.data.isoToMillis
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelativeTimeTest {
    private val at = "2026-09-29T00:00:00Z"
    private val atMillis = isoToMillis(at)!!

    @Test
    fun justNowUnderAMinute() {
        assertTrue(isJustNow(at, atMillis))
        assertTrue(isJustNow(at, atMillis + 59_999))
        // A timestamp ahead of the watch clock (skew, stale `now`) is still just now.
        assertTrue(isJustNow(at, atMillis - 5_000))
    }

    @Test
    fun notJustNowFromAMinute() {
        assertFalse(isJustNow(at, atMillis + 60_000))
        assertFalse(isJustNow(at, atMillis + 3_600_000))
    }

    @Test
    fun notJustNowWithoutATime() {
        assertFalse(isJustNow(null, atMillis))
        assertFalse(isJustNow("", atMillis))
        assertFalse(isJustNow("not a time", atMillis))
    }
}
