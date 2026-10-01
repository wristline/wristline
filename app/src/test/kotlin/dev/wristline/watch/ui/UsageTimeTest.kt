package dev.wristline.watch.ui

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTimeTest {
    private fun en(minutes: Long) = remainingFullText(minutes, "%1\$dd", "%1\$dh", "%1\$dm", "<1m")
    private fun ko(minutes: Long) = remainingFullText(minutes, "%1\$d일", "%1\$d시간", "%1\$d분", "1분 미만")

    @Test
    fun remainingIsInFullUnitsAZeroSecondUnitLeftOut() {
        assertEquals("<1m", en(0))
        assertEquals("5m", en(5))
        assertEquals("2h", en(120))
        assertEquals("2h 13m", en(133))
        assertEquals("23h 59m", en(1_439))
        assertEquals("1d", en(1_440))
        // From a day the minutes are dropped.
        assertEquals("1d", en(1_440 + 59))
        assertEquals("3d 4h", en(3 * 1_440 + 4 * 60 + 12))
        assertEquals("1분 미만", ko(0))
        assertEquals("5분", ko(5))
        assertEquals("2시간", ko(120))
        assertEquals("2시간 13분", ko(133))
        assertEquals("1일", ko(1_440))
        assertEquals("3일 4시간", ko(3 * 1_440 + 4 * 60 + 12))
    }

    @Test
    fun remainingCountsWholeMinutesLeft() {
        val now = 1_800_000_000_000L
        assertEquals("<1m", en(minutesLeft(now + 59_000, now)))
        assertEquals("2h 13m", en(minutesLeft(now + (133 * 60 + 59) * 1_000L, now)))
    }

    private val seoul = ZoneId.of("Asia/Seoul")

    // Tuesday 29 September 2026, 22:00 in Seoul.
    private val now = ZonedDateTime.of(2026, 9, 29, 22, 0, 0, 0, seoul).toInstant().toEpochMilli()

    private fun at(day: Int, hour: Int, minute: Int, month: Int = 9) =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, seoul).toInstant().toEpochMilli()

    private fun clock(resetsAt: Long, locale: Locale) = resetClock(resetsAt, now, seoul, locale, is24Hour = true)

    @Test
    fun resetClockIsTheTimeTodayTheWeekdayWithinTheWeekElseTheDate() {
        assertEquals("23:30", clock(at(29, 23, 30), Locale.ENGLISH))
        assertEquals("23:30", clock(at(29, 23, 30), Locale.KOREAN))
        // Past midnight it is tomorrow, whatever the hours left.
        assertEquals("Wed 0:13", clock(at(30, 0, 13), Locale.ENGLISH))
        assertEquals("수 0:13", clock(at(30, 0, 13), Locale.KOREAN))
        assertEquals("Fri 14:30", clock(at(2, 14, 30, month = 10), Locale.ENGLISH))
        assertEquals("금 14:30", clock(at(2, 14, 30, month = 10), Locale.KOREAN))
        // Six days on the weekday is still unambiguous; from seven it would name today's.
        assertEquals("Mon 9:00", clock(at(5, 9, 0, month = 10), Locale.ENGLISH))
        assertEquals("10/6 9:00", clock(at(6, 9, 0, month = 10), Locale.ENGLISH))
        assertEquals("10/8 14:30", clock(at(8, 14, 30, month = 10), Locale.KOREAN))
    }

    @Test
    fun resetClockDaysAreCountedInTheGivenZone() {
        // 15:13 UTC on the 29th: still today in UTC, already tomorrow in Seoul.
        val resetsAt = ZonedDateTime.of(2026, 9, 29, 15, 13, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val utcNow = ZonedDateTime.of(2026, 9, 29, 13, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("15:13", resetClock(resetsAt, utcNow, ZoneId.of("UTC"), Locale.ENGLISH, is24Hour = true))
        assertEquals("Wed 0:13", resetClock(resetsAt, utcNow, seoul, Locale.ENGLISH, is24Hour = true))
    }

    @Test
    fun resetClockFollowsTheTwelveHourSetting() {
        // The locale's own short time: its digits and day period, whatever the JDK's spacing.
        val text = resetClock(at(29, 23, 30), now, seoul, Locale.US, is24Hour = false)
        assertTrue(text, text.startsWith("11:30") && text.endsWith("PM"))
        val ko = resetClock(at(2, 14, 30, month = 10), now, seoul, Locale.KOREAN, is24Hour = false)
        assertEquals("금 오후 2:30", ko)
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
