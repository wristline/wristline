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

    // On screen when no locale; newer JDKs put a narrow no-break space before AM/PM.
    private fun itemTimeAt(iso: String, is24Hour: Boolean, locale: Locale? = null) =
        itemTime(iso, is24Hour, locale, seoul, today)?.replace('\u202F', ' ')

    @Test
    fun todayIsTheTimeAlone() {
        // 07:52Z is 16:52 in Seoul.
        assertEquals("16:52", itemTimeAt("2026-09-30T07:52:00Z", is24Hour = true))
        assertEquals("4:52 PM", itemTimeAt("2026-09-30T07:52:00Z", is24Hour = false))
    }

    @Test
    fun anotherDayLeadsWithTheDate() {
        assertEquals("Sep 29 16:52", itemTimeAt("2026-09-29T07:52:00Z", is24Hour = true))
        assertEquals("Sep 29 4:52 PM", itemTimeAt("2026-09-29T07:52:00Z", is24Hour = false))
    }

    @Test
    fun onScreenItIsEnglishInEveryLanguage() {
        val default = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.KOREAN, Locale.KOREA)) {
                Locale.setDefault(locale)
                assertEquals("4:52 PM", itemTimeAt("2026-09-30T07:52:00Z", is24Hour = false))
                assertEquals("Sep 29 16:52", itemTimeAt("2026-09-29T07:52:00Z", is24Hour = true))
            }
        } finally {
            Locale.setDefault(default)
        }
    }

    @Test
    fun readOutInTheWatchLanguage() {
        assertEquals("오후 4:52", itemTimeAt("2026-09-30T07:52:00Z", is24Hour = false, Locale.KOREA))
        assertEquals("9/29 오후 4:52", itemTimeAt("2026-09-29T07:52:00Z", is24Hour = false, Locale.KOREA))
        assertEquals("4:52 PM", itemTimeAt("2026-09-30T07:52:00Z", is24Hour = false, Locale.US))
        assertEquals("16:52", itemTimeAt("2026-09-30T07:52:00Z", is24Hour = true, Locale.KOREA))
    }

    @Test
    fun theDayIsTheWatchZones() {
        // Still the 29th in UTC, already the 30th in Seoul.
        assertEquals("12:30 AM", itemTimeAt("2026-09-29T15:30:00Z", is24Hour = false))
        assertEquals("00:30", itemTimeAt("2026-09-29T15:30:00Z", is24Hour = true))
    }

    @Test
    fun nothingWithoutATime() {
        assertNull(itemTimeAt("", is24Hour = true))
        assertNull(itemTimeAt("not a time", is24Hour = false, Locale.US))
    }
}
