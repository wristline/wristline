package dev.wristline.watch.data

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskContextTest {
    @Test
    fun unitsAreImperialOnlyForEnglishInTheUsLiberiaAndMyanmar() {
        assertEquals("imperial", unitsFor(Locale.US))
        assertEquals("imperial", unitsFor(Locale.forLanguageTag("en-LR")))
        assertEquals("imperial", unitsFor(Locale.forLanguageTag("en-MM")))
        assertEquals("metric", unitsFor(Locale.KOREA))
        assertEquals("metric", unitsFor(Locale.UK))
        assertEquals("metric", unitsFor(Locale.forLanguageTag("es-US")))
        assertEquals("metric", unitsFor(Locale.ENGLISH))
    }

    @Test
    fun timeIsIsoToTheSecondWithTheZonesOffset() {
        val now = Instant.parse("2026-10-27T05:03:12.678Z")
        assertEquals("2026-10-27T14:03:12+09:00", isoTime(now, ZoneId.of("Asia/Seoul")))
        assertEquals("2026-10-27T01:03:12-04:00", isoTime(now, ZoneId.of("America/New_York")))
        assertEquals("2026-10-27T05:03:12Z", isoTime(now, ZoneId.of("UTC")))
    }

    @Test
    fun contextCarriesZoneLocaleUnitsAndDevice() {
        val context = askContext(Instant.parse("2026-10-27T05:03:12Z"), ZoneId.of("Asia/Seoul"), Locale.KOREA, null)
        assertEquals(AskContext("2026-10-27T14:03:12+09:00", "Asia/Seoul", "ko-KR", "metric", device = "watch"), context)
        assertEquals(
            """{"time":"2026-10-27T14:03:12+09:00","tz":"Asia/Seoul","locale":"ko-KR","units":"metric","device":"watch"}""",
            WireJson.encodeToString(AskContext.serializer(), context),
        )
        val where = Located(AskLocation(37.592, 127.017, 20), "서울특별시 성북구 돈암동")
        val located = askContext(Instant.parse("2026-10-27T05:03:12Z"), ZoneId.of("Asia/Seoul"), Locale.KOREA, where)
        assertEquals(where.location, located.location)
        assertEquals(where.place, located.place)
    }

    @Test
    fun coordinatesAreRoundedToThreeDecimals() {
        assertEquals(37.592, roundCoordinate(37.59187), 0.0)
        assertEquals(127.017, roundCoordinate(127.0168), 0.0)
        assertEquals(-33.869, roundCoordinate(-33.86882), 0.0)
        assertEquals(AskLocation(37.592, 127.017, 13), Fix(37.59187, 127.0168, 12.6f).rounded())
        assertEquals(AskLocation(37.592, 127.017), Fix(37.59187, 127.0168, null).rounded())
    }

    @Test
    fun placeIsAdminAreaThenSubAdminOrLocalityThenSubLocality() {
        assertEquals("서울특별시 성북구 돈암동", placeName("서울특별시", "성북구", null, "돈암동"))
        assertEquals("California San Francisco Mission District", placeName("California", null, "San Francisco", "Mission District"))
        // A locality repeating the admin area (Seoul) and blanks are left out.
        assertEquals("서울특별시 돈암동", placeName("서울특별시", " ", "서울특별시", "돈암동"))
        assertNull(placeName(null, null, null, null))
    }

    @Test
    fun noPositionInTimeMeansNoLocation() = runBlocking {
        val started = System.nanoTime()
        val where = locate(current = { awaitCancellation() }, lastKnown = { null }, geocode = { "never" }, fixMs = 50, geocodeMs = 50, budgetMs = 100)
        assertNull(where)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
    }

    @Test
    fun aLateFixFallsBackToTheLastKnownOne() = runBlocking {
        val where = locate(
            current = { awaitCancellation() },
            lastKnown = { Fix(37.59187, 127.0168, 30f) },
            geocode = { "서울특별시 성북구 돈암동" },
            fixMs = 50, geocodeMs = 50, budgetMs = 500,
        )
        assertEquals(Located(AskLocation(37.592, 127.017, 30), "서울특별시 성북구 돈암동"), where)
    }

    @Test
    fun aLateOrFailedPlaceNameStillSendsTheLocation() = runBlocking {
        val fix = Fix(37.59187, 127.0168, 30f)
        val late = locate(current = { fix }, lastKnown = { null }, geocode = { awaitCancellation() }, fixMs = 50, geocodeMs = 50, budgetMs = 500)
        assertEquals(Located(AskLocation(37.592, 127.017, 30), null), late)
        val failed = locate(current = { fix }, lastKnown = { null }, geocode = { null }, fixMs = 50, geocodeMs = 50, budgetMs = 500)
        assertEquals(Located(AskLocation(37.592, 127.017, 30), null), failed)
    }

    @Test
    fun theGeocoderNeverRunsPastTheBudget() = runBlocking {
        val started = System.nanoTime()
        val where = locate(
            current = { kotlinx.coroutines.delay(80); Fix(1.0, 2.0, null) },
            lastKnown = { null },
            geocode = { awaitCancellation() },
            fixMs = 200, geocodeMs = 5_000, budgetMs = 150,
        )
        assertEquals(Located(AskLocation(1.0, 2.0), null), where)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
    }

    @Test
    fun askBodySendsContextAndSearch() {
        val context = AskContext("2026-10-27T14:03:12+09:00", "Asia/Seoul", "ko-KR", "metric", device = "watch")
        val json = WireJson.encodeToString(AskBody.serializer(), AskBody("codex", "what?", context = context, search = true))
        assertEquals(
            """{"provider":"codex","text":"what?","context":{"time":"2026-10-27T14:03:12+09:00","tz":"Asia/Seoul","locale":"ko-KR","units":"metric","device":"watch"},"search":true}""",
            json,
        )
        assertTrue(WireJson.encodeToString(AskBody.serializer(), AskBody("codex", "what?", search = false)).endsWith(""""search":false}"""))
    }
}
