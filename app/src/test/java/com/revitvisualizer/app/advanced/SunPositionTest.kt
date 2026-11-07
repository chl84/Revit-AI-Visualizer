package com.revitvisualizer.app.advanced

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class SunPositionTest {
    private val oslo = Place("Bø i Telemark", 59.41299, 9.0693, ZoneId.of("Europe/Oslo"), 67.0)

    @Test fun matchesNrelSpaPublishedExample() {
        // Reda/Andreas NREL/TP-560-34302, Appendix A.4. Geometric elevation
        // excludes the reference example's pressure-dependent refraction.
        val place = Place("NREL reference", 39.742476, -105.1786, ZoneOffset.ofHours(-7), 1830.14)
        val time = ZonedDateTime.of(2003, 10, 17, 12, 30, 30, 0, place.zone)
        val sun = SunPosition.calculate(place, time)
        assertEquals(194.34024, sun.azimuth, 0.005)
        assertEquals(39.872046, sun.elevation, 0.005)
        assertTrue(sun.apparentElevation > sun.elevation)
        assertEquals(Daylight.Sun, sun.daylight)
    }

    @Test fun usesDateSpecificDstAndRejectsMissingAndAmbiguousTimes() {
        val settings = EnvironmentSettings(place = oslo, date = LocalDate.of(2026, 3, 29), time = LocalTime.of(2, 30))
        assertThrows(EnvironmentException::class.java) { settings.localDateTime() }
        val autumn = settings.copy(date = LocalDate.of(2026, 10, 25))
        assertEquals(listOf(ZoneOffset.ofHours(2), ZoneOffset.ofHours(1)), autumn.offsets())
        assertThrows(EnvironmentException::class.java) { autumn.localDateTime() }
        val early = autumn.copy(offset = ZoneOffset.ofHours(2)).localDateTime()
        val late = autumn.copy(offset = ZoneOffset.ofHours(1)).localDateTime()
        assertEquals(3600L, late.toEpochSecond() - early.toEpochSecond())
        assertThrows(EnvironmentException::class.java) { autumn.copy(offset = ZoneOffset.UTC).localDateTime() }
        assertEquals(ZoneOffset.ofHours(1), settings.copy(date = LocalDate.of(2026, 1, 1)).localDateTime().offset)
        assertEquals(ZoneOffset.ofHours(2), settings.copy(date = LocalDate.of(2026, 7, 1)).localDateTime().offset)
    }

    @Test fun geographicPolesAndMidnightSunRemainFiniteWithNoWinterSun() {
        for (latitude in listOf(90.0, 69.6492, -90.0)) {
            val place = Place("Polar point", latitude, 0.0, ZoneOffset.UTC)
            val june = SunPosition.calculate(place, ZonedDateTime.of(2026, 6, 21, 0, 0, 0, 0, place.zone))
            val december = SunPosition.calculate(place, ZonedDateTime.of(2026, 12, 21, 12, 0, 0, 0, place.zone))
            assertTrue(june.azimuth.isFinite() && december.azimuth.isFinite())
            if (latitude > 0) {
                assertEquals(Daylight.Sun, june.daylight)
                assertTrue(december.daylight != Daylight.Sun)
            } else {
                assertEquals(Daylight.Night, june.daylight)
                assertEquals(Daylight.Sun, december.daylight)
            }
        }
    }

    @Test fun sameInstantAcrossDateLineAndFractionalOffsetsGivesSameSun() {
        val place = Place("Kathmandu", 27.7172, 85.324, ZoneId.of("Asia/Kathmandu"))
        val time = ZonedDateTime.of(2026, 2, 18, 0, 15, 0, 0, place.zone)
        assertEquals(ZoneOffset.ofHoursMinutes(5, 45), time.offset)
        val a = SunPosition.calculate(place, time)
        val b = SunPosition.calculate(place, time.withZoneSameInstant(ZoneId.of("Pacific/Honolulu")))
        assertEquals(a.azimuth, b.azimuth, 0.000001)
        assertEquals(a.elevation, b.elevation, 0.000001)
    }

    @Test fun southernHemisphereSunIsNorthAtSummerNoon() {
        val place = Place("Sydney", -33.8688, 151.2093, ZoneId.of("Australia/Sydney"))
        val sun = SunPosition.calculate(place, ZonedDateTime.of(2026, 12, 21, 12, 55, 0, 0, place.zone))
        assertTrue(sun.elevation > 78)
        assertTrue(sun.azimuth < 15 || sun.azimuth > 345)
        assertEquals(Daylight.Sun, sun.daylight)
    }

    @Test fun boundariesDistinguishTwilightFromDirectSunAndNight() {
        assertEquals(Daylight.Sun, SunPosition(0.0, -0.8, -0.2).daylight)
        assertEquals(Daylight.Civil, SunPosition(0.0, -1.0, -1.0).daylight)
        assertEquals(Daylight.Nautical, SunPosition(0.0, -7.0, -7.0).daylight)
        assertEquals(Daylight.Astronomical, SunPosition(0.0, -13.0, -13.0).daylight)
        assertEquals(Daylight.Night, SunPosition(0.0, -19.0, -19.0).daylight)
        assertFalse(SunPosition(0.0, 90.0, 90.0).directionDefined)
    }

    @Test fun solarOrientationRequiresBothPlaceAndNorthButAdvancedDoesNot() {
        assertFalse(EnvironmentSettings().enabled)
        for (settings in listOf(EnvironmentSettings(), EnvironmentSettings(place = oslo), EnvironmentSettings(northConfirmed = true))) {
            assertFalse(settings.usesSolarOrientation)
            assertNull(settings.resolve().sun)
            assertNull(settings.resolve().time)
        }
        assertEquals(oslo, EnvironmentSettings(place = oslo, northConfirmed = true).resolve().place)
        assertTrue(EnvironmentSettings(place = oslo, northConfirmed = true).resolve().usesSolarOrientation)
    }
}
