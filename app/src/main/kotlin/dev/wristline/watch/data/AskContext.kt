package dev.wristline.watch.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.TimeSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** How long a fresh fix may take before the last known one is used. */
internal const val FIX_MS = 5_000L

/** How long the place name may take. */
internal const val GEOCODE_MS = 3_000L

/** All of it: past this the ask goes without location (or without the place name). */
internal const val LOCATION_BUDGET_MS = 6_000L

/** A last known location older than this is not where the watch is now. */
private val LAST_KNOWN_MAX_AGE_NANOS = TimeUnit.MINUTES.toNanos(30)

/** Countries whose English locale reads miles and Fahrenheit. */
private val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM")

/** `imperial` for en-US, en-LR and en-MM; `metric` everywhere else. */
internal fun unitsFor(locale: Locale): String =
    if (locale.language == "en" && locale.country in IMPERIAL_COUNTRIES) "imperial" else "metric"

/** [now] in [zone], to the second, with the offset: `2026-10-27T14:03:12+09:00`. */
internal fun isoTime(now: Instant, zone: ZoneId): String =
    OffsetDateTime.ofInstant(now.truncatedTo(ChronoUnit.SECONDS), zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

/** Three decimals, about 100 m: enough for weather or "near here", not a street address. */
internal fun roundCoordinate(value: Double): Double = (value * 1000).roundToLong() / 1000.0

/** A position before rounding, as the platform reported it. */
internal data class Fix(val lat: Double, val lon: Double, val accuracyM: Float?)

internal fun Fix.rounded(): AskLocation = AskLocation(roundCoordinate(lat), roundCoordinate(lon), accuracyM?.roundToInt())

/** What the location setting adds to an ask: [place] is null when the name was not found in time. */
internal data class Located(val location: AskLocation, val place: String?)

/**
 * A coarse place name from an address's fields, largest first: `서울 성북구 돈암동`. The second part
 * is the sub-admin area, else the locality; blanks and repeats are left out. Null when none is set.
 */
internal fun placeName(adminArea: String?, subAdminArea: String?, locality: String?, subLocality: String?): String? {
    fun String?.part() = this?.trim()?.takeIf { it.isNotEmpty() }
    return listOfNotNull(adminArea.part(), subAdminArea.part() ?: locality.part(), subLocality.part())
        .distinct()
        .joinToString(" ")
        .ifEmpty { null }
}

/** The ask's `context`: now, in the watch's zone and language, with [where] when found. */
internal fun askContext(now: Instant, zone: ZoneId, locale: Locale, where: Located?): AskContext =
    AskContext(
        time = isoTime(now, zone),
        tz = zone.id,
        locale = locale.toLanguageTag(),
        units = unitsFor(locale),
        place = where?.place,
        location = where?.location,
        device = "watch",
    )

/**
 * Within [budgetMs]: a [current] fix (at most [fixMs]), else the [lastKnown] one; then its place
 * name from the rounded position (at most [geocodeMs], and never past the budget). Null when there
 * is no position; a position without a name when geocoding fails or is late.
 */
internal suspend fun locate(
    current: suspend () -> Fix?,
    lastKnown: () -> Fix?,
    geocode: suspend (AskLocation) -> String?,
    fixMs: Long = FIX_MS,
    geocodeMs: Long = GEOCODE_MS,
    budgetMs: Long = LOCATION_BUDGET_MS,
): Located? {
    val start = TimeSource.Monotonic.markNow()
    val fix = withTimeoutOrNull(fixMs) { current() } ?: lastKnown() ?: return null
    val location = fix.rounded()
    val left = budgetMs - start.elapsedNow().inWholeMilliseconds
    val place = if (left > 0) withTimeoutOrNull(minOf(geocodeMs, left)) { geocode(location) } else null
    return Located(location, place)
}

/** Either location permission: with only the coarse one the platform reports an approximate position. */
internal fun hasLocationPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Where the watch is, for an ask; callers check [hasLocationPermission] first. */
internal suspend fun findLocation(context: Context): Located? {
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    return locate(
        current = { currentFix(context, manager) },
        lastKnown = { lastKnownFix(manager) },
        geocode = { placeOf(context, it) },
    )
}

private fun Location.toFix() = Fix(latitude, longitude, if (hasAccuracy()) accuracy else null)

/**
 * One fresh fix: from the fused provider (on Wear OS it uses the phone's location while connected),
 * else GPS (with fine permission) or the network provider.
 */
@SuppressLint("MissingPermission") // Callers check hasLocationPermission.
private suspend fun currentFix(context: Context, manager: LocationManager): Fix? {
    val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val provider = when {
        manager.hasProvider(LocationManager.FUSED_PROVIDER) && manager.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
        fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        else -> return null
    }
    return suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        try {
            manager.getCurrentLocation(provider, signal, { it.run() }) { cont.resume(it?.toFix()) }
        } catch (_: SecurityException) {
            // The permission was revoked in the meantime.
            cont.resume(null)
        } catch (_: IllegalArgumentException) {
            // The provider went away in the meantime.
            cont.resume(null)
        }
    }
}

/** The newest last known location of any enabled provider, if at most 30 minutes old. */
@SuppressLint("MissingPermission") // Callers check hasLocationPermission.
private fun lastKnownFix(manager: LocationManager): Fix? {
    val now = SystemClock.elapsedRealtimeNanos()
    return manager.getProviders(true)
        .mapNotNull { provider ->
            try {
                manager.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            }
        }
        .filter { now - it.elapsedRealtimeNanos <= LAST_KNOWN_MAX_AGE_NANOS }
        .maxByOrNull { it.elapsedRealtimeNanos }
        ?.toFix()
}

/** The place name in the app's language, from the system geocoder; null where the watch has none. */
private suspend fun placeOf(context: Context, location: AskLocation): String? {
    if (!Geocoder.isPresent()) return null
    return suspendCancellableCoroutine { cont ->
        val listener = object : Geocoder.GeocodeListener {
            override fun onGeocode(addresses: MutableList<android.location.Address>) {
                cont.resume(addresses.firstOrNull()?.let { placeName(it.adminArea, it.subAdminArea, it.locality, it.subLocality) })
            }

            override fun onError(errorMessage: String?) = cont.resume(null)
        }
        try {
            Geocoder(context, Locale.getDefault()).getFromLocation(location.lat, location.lon, 1, listener)
        } catch (_: IllegalArgumentException) {
            cont.resume(null)
        }
    }
}
