package dev.wristline.watch.ui

import dev.wristline.watch.data.isoToMillis
import java.util.Locale
import org.junit.Assert.assertEquals
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

    @Test
    fun agoIsShortEnglishInTheLargestWholeUnit() {
        val minute = 60_000L
        assertEquals("now", agoText(at, atMillis))
        assertEquals("now", agoText(at, atMillis + 59_999))
        // Ahead of the watch clock: now.
        assertEquals("now", agoText(at, atMillis - 5 * minute))
        assertEquals("1m", agoText(at, atMillis + minute))
        assertEquals("5m", agoText(at, atMillis + 5 * minute + 59_000))
        assertEquals("59m", agoText(at, atMillis + 59 * minute))
        assertEquals("1h", agoText(at, atMillis + 60 * minute))
        assertEquals("3h", agoText(at, atMillis + (3 * 60 + 59) * minute))
        assertEquals("23h", agoText(at, atMillis + (24 * 60 - 1) * minute))
        assertEquals("1d", agoText(at, atMillis + 24 * 60 * minute))
        assertEquals("2d", agoText(at, atMillis + 2 * 24 * 60 * minute))
        assertEquals("30d", agoText(at, atMillis + 30L * 24 * 60 * minute))
        assertEquals("", agoText(null, atMillis))
        assertEquals("", agoText("not a time", atMillis))
    }

    @Test
    fun agoIsTheSameInEveryLanguage() {
        val default = Locale.getDefault()
        try {
            Locale.setDefault(Locale.KOREAN)
            assertEquals("3h", agoText(at, atMillis + 3 * 3_600_000L))
            assertEquals("now", agoText(at, atMillis))
        } finally {
            Locale.setDefault(default)
        }
    }
}
