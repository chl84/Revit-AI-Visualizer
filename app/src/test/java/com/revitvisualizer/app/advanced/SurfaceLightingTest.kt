package com.revitvisualizer.app.advanced

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class SurfaceLightingTest {
    private val place = Place("Ålesund", 62.47226, 6.1549, ZoneId.of("Europe/Oslo"))
    private val environment = ResolvedEnvironment(place, ZonedDateTime.of(2026, 11, 7, 12, 0, 0, 0, place.zone),
        SunPosition(175.342770, 11.08, 11.168694), Weather.Clear, false, NorthArrow())

    @Test fun alesundNoonDoesNotDirectlyLightTheNorthGable() {
        assertTrue(SceneSurface("north gable", 0.0, 0.0, Occlusion.clear).directSun(environment).contains("NO direct sunlight"))
        assertTrue(SceneSurface("south gable", 180.0, 0.0, Occlusion.clear).directSun(environment).startsWith("Faces the Sun:"))
        assertTrue(SceneSurface("north roof", 0.0, 45.0, Occlusion.clear).directSun(environment).contains("NO direct sunlight"))
        assertTrue(SceneSurface("flat roof", 0.0, 90.0, Occlusion.clear).directSun(environment).startsWith("Faces the Sun:"))
    }

    @Test fun weatherDarknessOcclusionAndUnknownNormalsOverrideDirectLight() {
        val facing = SceneSurface("south gable", 180.0, 0.0, Occlusion.clear)
        for (weather in listOf(Weather.Overcast, Weather.Rain)) {
            assertTrue(facing.directSun(environment.copy(weather = weather)).contains("requested cloud cover"))
        }
        assertTrue(facing.directSun(environment.copy(sun = SunPosition(175.0, -4.0, -4.0))).contains("Sun below horizon"))
        assertTrue(facing.copy(occlusion = Occlusion.blocked).directSun(environment).contains("obstruction blocks"))
        assertTrue(facing.copy(normalAzimuth = null, normalElevation = null).directSun(environment).contains("orientation uncertain"))
        assertTrue(facing.copy(normalAzimuth = environment.sun!!.azimuth + 90).directSun(environment).startsWith("Grazing"))
    }

    @Test fun impossibleNormalsAndFalseResolvedOrientationAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SceneSurface("bad", Double.NaN, 0.0, Occlusion.clear) }
        assertThrows(IllegalArgumentException::class.java) { SceneSurface("bad", 0.0, 91.0, Occlusion.clear) }
        assertThrows(IllegalArgumentException::class.java) { SceneSurface("bad", null, 0.0, Occlusion.clear) }
        assertThrows(IllegalArgumentException::class.java) { SceneOrientation(true, "north", "sun", "shadow", emptyList(), emptyList()) }
    }
}
