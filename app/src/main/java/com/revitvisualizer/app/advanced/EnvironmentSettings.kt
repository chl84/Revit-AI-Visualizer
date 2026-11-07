package com.revitvisualizer.app.advanced

import com.revitvisualizer.app.imaging.ImagePoint
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs
import net.e175.klaus.solarpositioning.Atmosphere
import net.e175.klaus.solarpositioning.DeltaT
import net.e175.klaus.solarpositioning.SolarPositions

class EnvironmentException(message: String) : IllegalArgumentException(message)

data class Place(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val zone: ZoneId,
    val elevation: Double = 0.0,
) {
    init {
        require(name.isNotBlank() && name.length <= 300)
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(elevation.isFinite() && elevation in -500.0..9000.0)
    }
    val atPole get() = abs(latitude) > 89.999999
    val coordinates get() = String.format(Locale.ROOT, "%.5f, %.5f", latitude, longitude)
}

enum class Weather(val label: String, val instruction: String) {
    Clear("Clear", "Clear sky. If the Sun is above the horizon, use direct sunlight and coherent, sharply defined shadows."),
    PartlyCloudy("Partly cloudy", "Partly cloudy sky. Clouds may soften or occlude the computed Sun, but must never move it or reverse shadow directions."),
    Overcast("Overcast", "Continuous overcast. Diffuse sky illumination; no visible solar disc, sunbeams or hard directional solar shadows."),
    Rain("Rain", "Rain with overcast sky, wet surfaces and plausible reflections. No visible solar disc, sunbeams or hard directional solar shadows."),
}

/** Session-only form state owned by the ViewModel, separate from the selected place. */
data class ManualPlaceDraft(
    val name: String,
    val latitude: String,
    val longitude: String,
    val zone: String,
    val initialPlace: Place? = null,
    val error: String? = null,
)

/** Both endpoints are normalized in the final prepared image (not the camera/screen). */
data class NorthArrow(
    val tail: ImagePoint = ImagePoint(0.15f, 0.78f),
    val head: ImagePoint = ImagePoint(0.15f, 0.48f),
) {
    fun valid(width: Int, height: Int): Boolean {
        val points = listOf(tail, head)
        if (points.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0.04f..0.96f || it.y !in 0.04f..0.96f }) return false
        return kotlin.math.hypot((head.x - tail.x) * width, (head.y - tail.y) * height) >= minOf(width, height) * 0.08f
    }
}

data class EnvironmentSettings(
    val enabled: Boolean = false,
    val place: Place? = null,
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.NOON,
    val offset: ZoneOffset? = null,
    val weather: Weather = Weather.Clear,
    val snow: Boolean = false,
    val north: NorthArrow = NorthArrow(),
    val northConfirmed: Boolean = false,
) {
    fun offsets(): List<ZoneOffset> = place?.zone?.rules?.getValidOffsets(LocalDateTime.of(date, time)).orEmpty()

    fun localDateTime(): ZonedDateTime {
        val location = place ?: throw EnvironmentException("Find and select a place, or enter coordinates and a time zone.")
        if (date.year !in 1900..2100) throw EnvironmentException("Choose a date between 1900 and 2100.")
        val local = LocalDateTime.of(date, time)
        val offsets = offsets()
        if (offsets.isEmpty()) throw EnvironmentException("This local time does not exist because the clocks move forward. Choose another time.")
        val chosen = if (offsets.size == 1) offsets.first() else offset?.takeIf { it in offsets }
            ?: throw EnvironmentException("This local time occurs twice. Select its UTC offset below the time.")
        return ZonedDateTime.ofStrict(local, chosen, location.zone)
    }

    fun resolve(): ResolvedEnvironment {
        val local = localDateTime()
        if (!northConfirmed) throw EnvironmentException("Place and confirm the north arrow on the prepared image.")
        return ResolvedEnvironment(place!!, local, SunPosition.calculate(place, local), weather, snow, north)
    }
}

enum class Daylight(val label: String) { Sun("Sun above horizon"), Civil("Civil twilight"), Nautical("Nautical twilight"), Astronomical("Astronomical twilight"), Night("Night") }

data class SunPosition(val azimuth: Double, val elevation: Double, val apparentElevation: Double) {
    val daylight get() = when {
        elevation >= -0.833 -> Daylight.Sun // standard sea-level upper-limb/refraction threshold
        elevation >= -6 -> Daylight.Civil
        elevation >= -12 -> Daylight.Nautical
        elevation >= -18 -> Daylight.Astronomical
        else -> Daylight.Night
    }
    val directionDefined get() = abs(elevation) < 89.999

    companion object {
        private val spa = SolarPositions.spa()
        fun calculate(place: Place, time: ZonedDateTime): SunPosition {
            val deltaT = DeltaT.estimate(time.withZoneSameInstant(ZoneOffset.UTC))
            val geometric = spa.at(time, place.latitude, place.longitude, place.elevation, deltaT)
            val apparent = spa.at(time, place.latitude, place.longitude, place.elevation, deltaT, Atmosphere(1010.0, 10.0))
            return SunPosition(geometric.azimuth(), geometric.elevation(), apparent.elevation())
        }
    }
}

data class ResolvedEnvironment(val place: Place, val time: ZonedDateTime, val sun: SunPosition,
    val weather: Weather, val snow: Boolean, val north: NorthArrow)
