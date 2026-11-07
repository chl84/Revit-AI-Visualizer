package com.revitvisualizer.app.advanced

import android.content.Context
import android.location.Address
import android.location.Geocoder
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(Geocoder::class)
class DuplicatePlaceGeocoder {
    @Implementation fun __constructor__(context: Context, locale: Locale) {}
    @Implementation fun getFromLocationName(query: String, maxResults: Int, listener: Geocoder.GeocodeListener) {
        listener.onGeocode(mutableListOf(Address(Locale.ENGLISH).apply {
            latitude = if (query == "nearby") 59.41291 else 59.41299
            longitude = 9.0693
            setAddressLine(0, "Device geocoder result")
        }))
    }
    companion object { @JvmStatic @Implementation fun isPresent() = true }
}

/** Hermetic provider responses; no public geocoding or OpenAI requests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [DuplicatePlaceGeocoder::class])
class PlaceSearchTest {
    private fun verifyKnownZoneSurvives(query: String) = runBlocking {
        var searchRequests = 0
        var forecastRequests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val forecast = chain.request().url.host == "api.open-meteo.com"
            if (forecast) forecastRequests++ else searchRequests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (forecast) 503 else 200).message("Local fixture")
                .body((if (forecast) "" else """{"results":[{"name":"Bø","admin1":"Telemark","country":"Norway","latitude":59.41299,"longitude":9.0693,"timezone":"Europe/Oslo","elevation":67}]}""")
                    .toResponseBody()).build()
        }.build()
        val search = PlaceSearch(RuntimeEnvironment.getApplication(), client)
        val results = search.search(query)
        assertEquals(1, results.size)
        assertEquals("Europe/Oslo", results.single().zone)
        val place = search.resolve(results.single())
        assertEquals(ZoneId.of("Europe/Oslo"), place.zone)
        assertEquals("Bø, Telemark, Norway", place.name)
        assertEquals(59.41299, place.latitude, 0.0)
        assertEquals(9.0693, place.longitude, 0.0)
        assertEquals(67.0, place.elevation, 0.0)
        assertEquals(1, searchRequests)
        assertEquals(0, forecastRequests)
        assertEquals(results, search.search(query))
        assertEquals(1, searchRequests)
    }

    @Test fun duplicatePointKeepsKnownZoneEvenWhenForecastServiceIsUnavailable() = verifyKnownZoneSurvives("same point")

    @Test fun nearbyDuplicateKeepsZonedProvidersCoordinatesAndElevationTogether() = verifyKnownZoneSurvives("nearby")
}
