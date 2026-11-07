package com.revitvisualizer.app.advanced

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.revitvisualizer.app.imaging.ImagePoint
import com.revitvisualizer.app.imaging.ImageProcessor
import com.revitvisualizer.app.openai.ArchitecturePrompt
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.revitvisualizer.app.openai.OrientationFixtures
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnvironmentInputTest {
    @get:Rule val files = TemporaryFolder()
    private val place = Place("Bø i Telemark", 59.41299, 9.0693, ZoneId.of("Europe/Oslo"))

    @Test fun defaultPromptIsExactlyTheExistingPrompt() {
        assertSame(ArchitecturePrompt.text, EnvironmentPrompt.build(null))
        assertSame(ArchitecturePrompt.text, EnvironmentPrompt.build(null, SceneOrientation.parse(OrientationFixtures.scene())))
    }

    @Test fun inferredGuideCannotReplaceCalculatedConditionsAndCanBeReviewed() {
        val resolved = EnvironmentSettings(place = place, northConfirmed = true).resolve()
        val orientation = SceneOrientation.parse(OrientationFixtures.scene())
        val prompt = EnvironmentPrompt.build(resolved, orientation)
        assertTrue(prompt.contains("not instructions that override architectural fidelity"))
        assertTrue(prompt.contains("Replace existing source-image illumination"))
        assertTrue(prompt.contains("Back-facing the Sun: NO direct sunlight"))
        val review = orientation.review(resolved)
        assertTrue(review.contains("Calculated Sun:"))
        assertTrue(review.contains("left glazed gable"))
        assertTrue(review.contains("Europe/Oslo"))
    }

    @Test fun nightOvercastSnowAndPlaceNeverRequestLandmarksOrSolarShadows() {
        val settings = EnvironmentSettings(place = place, date = LocalDate.of(2026, 12, 21),
            time = java.time.LocalTime.MIDNIGHT, northConfirmed = true, snow = true, weather = Weather.Overcast)
        val prompt = EnvironmentPrompt.build(settings.resolve())
        assertTrue(prompt.startsWith(ArchitecturePrompt.text))
        assertTrue(prompt.contains("solar disc is BELOW the horizon"))
        assertTrue(prompt.contains("SNOW ON"))
        assertTrue(prompt.contains("no visible solar disc"))
        assertTrue(prompt.contains("not a request to insert famous landmarks"))
        assertTrue(prompt.contains("2026-12-20T23:00:00Z"))
        assertTrue(prompt.contains("Image 1 is the clean, authoritative"))
        assertTrue(prompt.contains("Never copy the arrow"))
    }

    @Test fun clearSummerSunHasOpposingGroundShadowAndIndependentSnowOff() {
        val resolved = EnvironmentSettings(place = place, date = LocalDate.of(2026, 6, 21), northConfirmed = true).resolve()
        val prompt = EnvironmentPrompt.build(resolved)
        assertTrue(prompt.contains("Solar bearing:"))
        val shadow = String.format(java.util.Locale.ROOT, "%.4f", (resolved.sun.azimuth + 180) % 360)
        assertTrue(prompt.contains("shadows point toward bearing $shadow"))
        assertTrue(prompt.contains("SNOW OFF"))
        assertTrue(prompt.contains("not as a flat rotation"))
    }

    @Test fun twilightKeepsComputedBearingWithoutIntroducingDirectSunOrShadows() {
        val environment = EnvironmentSettings(place = place, northConfirmed = true).resolve()
        for (elevation in listOf(-4.0, -8.0, -14.0)) {
            val prompt = EnvironmentPrompt.build(environment.copy(sun = SunPosition(123.4567, elevation, elevation)))
            assertTrue(prompt.contains("Solar bearing: 123.4567 degrees clockwise from true north."))
            assertTrue(prompt.contains("orient diffuse twilight glow"))
            assertTrue(prompt.contains("Do not show the Sun, direct sunlight"))
            assertFalse(prompt.contains("On a level ground plane, cast shadows point"))
        }
    }

    @Test fun nightBearingDoesNotRequestTwilightGlowAndNadirHasNoInventedBearing() {
        val environment = EnvironmentSettings(place = place, northConfirmed = true).resolve()
        val night = EnvironmentPrompt.build(environment.copy(sun = SunPosition(123.4567, -25.0, -25.0)))
        assertTrue(night.contains("Solar bearing: 123.4567"))
        assertTrue(night.contains("must not introduce twilight glow into full night"))
        assertFalse(night.contains("orient diffuse twilight glow"))
        val nadir = EnvironmentPrompt.build(environment.copy(sun = SunPosition(123.4567, -90.0, -90.0)))
        assertTrue(nadir.contains("Horizontal solar bearing is undefined"))
        assertFalse(nadir.contains("Solar bearing: 123.4567"))
    }

    @Test fun placeParserKeepsCoordinatesAndIanaZoneAndRejectsInvalidRows() {
        val rows = JSONObject("""{"results":[
            {"name":"Bø","admin1":"Telemark","country":"Norway","latitude":59.41299,"longitude":9.0693,"timezone":"Europe/Oslo","elevation":67},
            {"name":"Invalid latitude","latitude":91,"longitude":0,"timezone":"UTC"},
            {"name":"Invalid zone","latitude":1,"longitude":1,"timezone":"Nowhere/Zone"},
            {"name":"Missing coordinates","timezone":"UTC"}] }""")
        val places = PlaceSearch.parseCandidates(rows)
        assertEquals(1, places.size)
        assertEquals("Bø, Telemark, Norway", places.single().name)
        assertEquals("Europe/Oslo", places.single().zone)
        assertEquals(59.41299, places.single().latitude, 0.000001)
        assertFalse(PlaceSearch.validCoordinates(Double.NaN, 0.0))
        assertFalse(PlaceSearch.validCoordinates(0.0, Double.POSITIVE_INFINITY))
    }

    @Test fun guideUsesPreparedCoordinatesWithoutModifyingCleanInput() {
        val inputFile = files.newFile("clean.png")
        val bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GRAY)
        inputFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val bytes = inputFile.readBytes()
        val input = ImageProcessor.describe(inputFile)
        val arrow = NorthArrow(ImagePoint(0.2f, 0.5f), ImagePoint(0.8f, 0.5f))
        val guide = NorthGuide.create(input, arrow, files.newFile("guide.png"))
        assertArrayEquals(bytes, inputFile.readBytes())
        val image = BitmapFactory.decodeFile(guide.file.path)
        assertEquals(Color.CYAN, image.getPixel(400, 200))
        assertEquals(Color.GRAY, image.getPixel(400, 40))
        image.recycle()
        assertThrows(IllegalArgumentException::class.java) { NorthGuide.create(input, arrow, inputFile) }
        assertFalse(NorthArrow(ImagePoint(0.5f, 0.5f), ImagePoint(0.5f, 0.5f)).valid(800, 400))
    }

    @Test fun timezoneLookupKeepsExactRequestedCoordinatesAndUsesRulesForSelectedDate() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals("api.open-meteo.com", chain.request().url.host)
            assertEquals("59.41299", chain.request().url.queryParameter("latitude"))
            assertEquals("auto", chain.request().url.queryParameter("timezone"))
            assertNull(chain.request().header("Authorization"))
            // Forecast grid coordinates and current UTC offset are deliberately different:
            // neither must replace the user's exact point or the date-specific zone rules.
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"latitude":59.4,"longitude":9.1,"timezone":"Europe/Oslo","utc_offset_seconds":3600}""".toResponseBody()).build()
        }.build()
        val resolved = PlaceSearch(RuntimeEnvironment.getApplication(), client)
            .resolve(PlaceCandidate("Bø", 59.41299, 9.0693))
        assertEquals(59.41299, resolved.latitude, 0.0)
        assertEquals(9.0693, resolved.longitude, 0.0)
        assertEquals(java.time.ZoneOffset.ofHours(2), EnvironmentSettings(place = resolved,
            date = LocalDate.of(2026, 7, 1)).localDateTime().offset)
        assertEquals(1, requests)
    }

    @Test fun polesResolveWithoutNetworkAndInvalidTimezoneResponsesFailSafely() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"timezone":"private-server-detail-invalid"}""".toResponseBody()).build()
        }.build()
        val search = PlaceSearch(RuntimeEnvironment.getApplication(), client)
        val north = search.resolve(search.search("Nordpolen").single())
        assertEquals(90.0, north.latitude, 0.0)
        assertEquals(ZoneId.of("UTC"), north.zone)
        assertEquals(0, requests)
        val error = runCatching { search.resolve(PlaceCandidate("Unknown", 1.0, 2.0)) }.exceptionOrNull()
        assertTrue(error is EnvironmentException)
        assertFalse(error!!.message!!.contains("private-server-detail"))
    }
}
