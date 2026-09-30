package dev.wristline.watch.ui

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ItemTimeTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 9, 30)

    // Newer JDKs put a narrow no-break space before AM/PM.
    private fun itemTimeAt(iso: String, locale: Locale) = itemTime(iso, locale, seoul, today)?.replace(' ', ' ')

    @Test
    fun todayIsTheTimeAlone() {
        // 07:52Z is 16:52 in Seoul.
        assertEquals("4:52 PM", itemTimeAt("2026-09-30T07:52:00Z", Locale.US))
        assertEquals("오후 4:52", itemTimeAt("2026-09-30T07:52:00Z", Locale.KOREA))
    }

    @Test
    fun anotherDayLeadsWithTheDate() {
        assertEquals("9/29 4:52 PM", itemTimeAt("2026-09-29T07:52:00Z", Locale.US))
        assertEquals("9/29 오후 4:52", itemTimeAt("2026-09-29T07:52:00Z", Locale.KOREA))
    }

    @Test
    fun theDayIsTheWatchZones() {
        // Still the 29th in UTC, already the 30th in Seoul.
        assertEquals("12:30 AM", itemTimeAt("2026-09-29T15:30:00Z", Locale.US))
    }

    @Test
    fun nothingWithoutATime() {
        assertNull(itemTimeAt("", Locale.US))
        assertNull(itemTimeAt("not a time", Locale.US))
    }
}
