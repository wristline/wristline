package dev.wristline.watch.ui

import dev.wristline.watch.data.UsageWindow
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTimeTest {
    @Test
    fun remainingIsInFullUnitsAZeroSecondUnitLeftOut() {
        assertEquals("in <1m", remainingIn(0))
        assertEquals("in 5m", remainingIn(5))
        assertEquals("in 2h", remainingIn(120))
        assertEquals("in 2h 13m", remainingIn(133))
        assertEquals("in 23h 59m", remainingIn(1_439))
        assertEquals("in 1d", remainingIn(1_440))
        // From a day the minutes are dropped.
        assertEquals("in 1d", remainingIn(1_440 + 59))
        assertEquals("in 3d 4h", remainingIn(3 * 1_440 + 4 * 60 + 12))
    }

    @Test
    fun remainingCountsWholeMinutesLeft() {
        val now = 1_800_000_000_000L
        assertEquals("in <1m", remainingIn(minutesLeft(now + 59_000, now)))
        assertEquals("in 2h 13m", remainingIn(minutesLeft(now + (133 * 60 + 59) * 1_000L, now)))
        // Past the reset: none left.
        assertEquals("in <1m", remainingIn(minutesLeft(now - 60_000, now)))
    }

    private val seoul = ZoneId.of("Asia/Seoul")

    // Tuesday 29 September 2026, 22:00 in Seoul.
    private val now = ZonedDateTime.of(2026, 9, 29, 22, 0, 0, 0, seoul).toInstant().toEpochMilli()

    private fun at(day: Int, hour: Int, minute: Int, month: Int = 9) =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, seoul).toInstant().toEpochMilli()

    private fun clock(resetsAt: Long, is24Hour: Boolean = true, compact: Boolean = false) =
        resetClockText(resetsAt, now, seoul, is24Hour, compact)

    @Test
    fun resetClockIsTheTimeTodayTheWeekdayWithinSevenDaysElseTheDate() {
        assertEquals("23:30", clock(at(29, 23, 30)))
        // Tomorrow, from midnight on, whatever the hours left.
        assertEquals("Wed 00:13", clock(at(30, 0, 13)))
        assertEquals("Fri 14:30", clock(at(2, 14, 30, month = 10)))
        assertEquals("Mon 09:00", clock(at(5, 9, 0, month = 10)))
        // Seven days on, today's weekday is next week's: today shows the time alone.
        assertEquals("Tue 09:00", clock(at(6, 9, 0, month = 10)))
        assertEquals("Tue 23:59", clock(at(6, 23, 59, month = 10)))
        // From eight days the date.
        assertEquals("Oct 7 00:00", clock(at(7, 0, 0, month = 10)))
        assertEquals("Oct 8 14:30", clock(at(8, 14, 30, month = 10)))
    }

    @Test
    fun resetClockDaysAreCountedInTheGivenZone() {
        // 15:13 UTC on the 29th: still today in UTC, already tomorrow in Seoul.
        val resetsAt = ZonedDateTime.of(2026, 9, 29, 15, 13, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val utcNow = ZonedDateTime.of(2026, 9, 29, 13, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("15:13", resetClockText(resetsAt, utcNow, ZoneId.of("UTC"), is24Hour = true))
        assertEquals("Wed 00:13", resetClockText(resetsAt, utcNow, seoul, is24Hour = true))
    }

    @Test
    fun resetClockFollowsTheTwelveHourSetting() {
        assertEquals("11:30 PM", clock(at(29, 23, 30), is24Hour = false))
        // The hour is always two digits.
        assertEquals("07:53 PM", clock(at(29, 19, 53), is24Hour = false))
        assertEquals("19:53", clock(at(29, 19, 53)))
        assertEquals("Thu 07:40 PM", clock(at(1, 19, 40, month = 10), is24Hour = false))
        assertEquals("Fri 02:30 PM", clock(at(2, 14, 30, month = 10), is24Hour = false))
        assertEquals("Wed 12:13 AM", clock(at(30, 0, 13), is24Hour = false))
        assertEquals("Oct 8 09:05 AM", clock(at(8, 9, 5, month = 10), is24Hour = false))
        // Compact, for the limit card when the full form does not fit.
        assertEquals("Fri 02:30p", clock(at(2, 14, 30, month = 10), is24Hour = false, compact = true))
        assertEquals("Wed 12:13a", clock(at(30, 0, 13), is24Hour = false, compact = true))
        assertEquals("Thu 07:40p", clock(at(1, 19, 40, month = 10), is24Hour = false, compact = true))
        assertEquals("12:00p", resetClockText(at(30, 12, 0), at(30, 9, 0), seoul, is24Hour = false, compact = true))
        // Compact changes nothing in 24 hours.
        assertEquals("Fri 14:30", clock(at(2, 14, 30, month = 10), compact = true))
    }

    @Test
    fun resetClockIsTheSameInEveryLanguage() {
        val times = listOf(at(29, 23, 30), at(30, 0, 13), at(2, 14, 30, month = 10), at(8, 14, 30, month = 10))
        val formats = { times.flatMap { t -> listOf(clock(t), clock(t, is24Hour = false), clock(t, is24Hour = false, compact = true)) } }
        val default = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val us = formats()
            Locale.setDefault(Locale.KOREAN)
            assertEquals(us, formats())
            Locale.setDefault(Locale.KOREA)
            assertEquals(us, formats())
        } finally {
            Locale.setDefault(default)
        }
    }

    // Newer JDKs put a narrow no-break space before AM/PM.
    private fun words(resetsAt: Long, locale: Locale, today: String, is24Hour: Boolean = true) =
        resetClockWords(resetsAt, now, seoul, locale, is24Hour, today).replace(' ', ' ')

    @Test
    fun resetClockWordsAreInTheWatchLanguage() {
        assertEquals("today 23:30", words(at(29, 23, 30), Locale.US, "today %1\$s"))
        assertEquals("오늘 23:30", words(at(29, 23, 30), Locale.KOREAN, "오늘 %1\$s"))
        assertEquals("Friday 14:30", words(at(2, 14, 30, month = 10), Locale.US, "today %1\$s"))
        assertEquals("금요일 14:30", words(at(2, 14, 30, month = 10), Locale.KOREAN, "오늘 %1\$s"))
        assertEquals("Friday 2:30 PM", words(at(2, 14, 30, month = 10), Locale.US, "today %1\$s", is24Hour = false))
        assertEquals("금요일 오후 2:30", words(at(2, 14, 30, month = 10), Locale.KOREAN, "오늘 %1\$s", is24Hour = false))
        // Seven days on still the weekday; from eight the date, in full.
        assertEquals("Tuesday 09:00", words(at(6, 9, 0, month = 10), Locale.US, "today %1\$s"))
        assertEquals("화요일 09:00", words(at(6, 9, 0, month = 10), Locale.KOREAN, "오늘 %1\$s"))
        assertEquals("October 7, 2026 14:30", words(at(7, 14, 30, month = 10), Locale.US, "today %1\$s"))
        assertEquals("2026년 10월 7일 14:30", words(at(7, 14, 30, month = 10), Locale.KOREAN, "오늘 %1\$s"))
    }

    @Test
    fun windowNamesAreShortEnglish() {
        assertEquals("5h", windowAbbrev(UsageWindow("primary", 3.0, minutes = 300)))
        assertEquals("7d", windowAbbrev(UsageWindow("secondary", 20.0, minutes = 10_080)))
        assertEquals("30d", windowAbbrev(UsageWindow("secondary", 20.0, minutes = 43_200)))
        assertEquals("90m", windowAbbrev(UsageWindow("primary", 20.0, minutes = 90)))
        // Claude Code's ids without a length, and the bridge's label first.
        assertEquals("7d", windowAbbrev(UsageWindow("7d", 20.0)))
        assertEquals("7d Opus", windowAbbrev(UsageWindow("7d_opus", 30.0, label = "7d Opus")))
        // Unknown length: the id.
        assertEquals("primary", windowAbbrev(UsageWindow("primary", 20.0)))
    }

    @Test
    fun spokenWindowNamesKeepWhatTheLabelAddsToTheLength() {
        // `7d Opus` is read as the weekly limit's words and ` Opus`; a plain length as the words alone.
        assertEquals(" Opus", labelAfterLength(UsageWindow("7d_opus", 30.0, minutes = 10_080, label = "7d Opus")))
        assertEquals("", labelAfterLength(UsageWindow("5h", 42.0, minutes = 300, label = "5h")))
        assertEquals("", labelAfterLength(UsageWindow("primary", 3.0, minutes = 300)))
        // A label that is not a length is read as it is.
        assertNull(labelAfterLength(UsageWindow("spend", 12.0, label = "Spend")))
    }

    @Test
    fun aWindowIsOverFromItsResetTime() {
        val now = 1_800_000_000_000L
        assertFalse(resetPassed(now + 1, now))
        assertTrue(resetPassed(now, now))
        assertTrue(resetPassed(now - 60_000, now))
        // Without a reset time nothing ends it.
        assertFalse(resetPassed(null, now))
    }
}
