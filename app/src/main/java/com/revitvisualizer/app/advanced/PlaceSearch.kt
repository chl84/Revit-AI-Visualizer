package com.revitvisualizer.app.advanced

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import java.io.IOException
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

data class PlaceCandidate(val name: String, val latitude: Double, val longitude: Double,
    val zone: String? = null, val elevation: Double = 0.0) {
    val coordinates get() = String.format(Locale.ROOT, "%.5f, %.5f", latitude, longitude)
}

/** No device location permission, API credential or image is sent to these providers. */
class PlaceSearch(private val context: Context, private val client: OkHttpClient = OkHttpClient.Builder()
    .callTimeout(20, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()) {
    private val cache = mutableMapOf<String, List<PlaceCandidate>>()

    suspend fun search(query: String): List<PlaceCandidate> = coroutineScope {
        val text = query.trim().take(200)
        if (text.length < 2) throw EnvironmentException("Enter at least two characters for the place.")
        val key = text.lowercase(Locale.ROOT)
        cache[key]?.let { return@coroutineScope it }
        val pole = when (key) {
            "nordpolen", "north pole", "the north pole" -> PlaceCandidate("North Pole · UTC · 0° meridian reference", 90.0, 0.0, "UTC")
            "sydpolen", "sørpolen", "south pole", "the south pole" -> PlaceCandidate("South Pole · UTC · 0° meridian reference", -90.0, 0.0, "UTC")
            else -> null
        }
        if (pole != null) return@coroutineScope listOf(pole)
        // Device geocoding covers natural features as well as settlements. GeoNames
        // supplies explicit time zones and a fallback where no Android provider exists.
        val device = async { deviceSearch(text) }
        val named = async {
            try {
                val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
                    .addQueryParameter("name", text).addQueryParameter("count", "6")
                    .addQueryParameter("language", "en").addQueryParameter("format", "json").build()
                parseCandidates(json(url.toString()))
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { emptyList() }
        }
        val results = (device.await() + named.await()).groupBy {
            "${(it.latitude * 1000).toInt()}:${(it.longitude * 1000).toInt()}"
        }.values.map { matches ->
            // Retain the complete zoned result: copying its zone onto a nearby
            // provider's coordinates could mismatch the point at a time-zone border.
            matches.firstOrNull { it.zone != null } ?: matches.first()
        }.take(8)
        if (results.isEmpty()) throw EnvironmentException("No place was found. Try a more specific name or use coordinates and a time zone.")
        if (cache.size >= 32) cache.clear()
        cache[key] = results
        results
    }

    suspend fun resolve(candidate: PlaceCandidate): Place {
        val zone = candidate.zone ?: json("https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", candidate.latitude.toString())
            .addQueryParameter("longitude", candidate.longitude.toString())
            .addQueryParameter("timezone", "auto").addQueryParameter("forecast_days", "1").build().toString())
            .optString("timezone")
        try {
            return Place(candidate.name, candidate.latitude, candidate.longitude, ZoneId.of(zone), candidate.elevation)
        } catch (_: Exception) {
            throw EnvironmentException("The place's time zone could not be resolved. Enter its coordinates and IANA time zone manually.")
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun deviceSearch(query: String): List<PlaceCandidate> {
        if (!Geocoder.isPresent()) return emptyList()
        return try {
            val geocoder = Geocoder(context, Locale.ENGLISH)
            val addresses: List<Address> = if (Build.VERSION.SDK_INT >= 33) {
                withTimeoutOrNull(8_000) {
                    suspendCancellableCoroutine { continuation ->
                        geocoder.getFromLocationName(query, 4, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (continuation.isActive) continuation.resume(addresses)
                            }
                            override fun onError(errorMessage: String?) {
                                if (continuation.isActive) continuation.resume(emptyList())
                            }
                        })
                    }
                }.orEmpty()
            } else withContext(Dispatchers.IO) { geocoder.getFromLocationName(query, 4).orEmpty() }
            addresses.filter { it.hasLatitude() && it.hasLongitude() }.mapNotNull { address ->
                val name = (address.getAddressLine(0) ?: listOfNotNull(address.featureName, address.adminArea, address.countryName)
                    .distinct().joinToString(", ")).take(300)
                if (name.isBlank() || !validCoordinates(address.latitude, address.longitude)) null
                else PlaceCandidate(name, address.latitude, address.longitude)
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { emptyList() }
    }

    private suspend fun json(url: String): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", "Vizruna/0.1.6 (Android place lookup)").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(EnvironmentException("Place lookup is unavailable. Try again or enter coordinates and a time zone."))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val json = response.use {
                        if (!it.isSuccessful) throw IOException()
                        val source = it.body?.source() ?: throw IOException()
                        source.request(1_000_001)
                        if (source.buffer.size > 1_000_000) throw IOException()
                        JSONObject(source.readUtf8())
                    }
                    if (continuation.isActive) continuation.resume(json)
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(EnvironmentException("Place lookup is unavailable. Try again or enter coordinates and a time zone."))
                }
            }
        })
    }

    companion object {
        fun validCoordinates(latitude: Double, longitude: Double) = latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0

        internal fun parseCandidates(json: JSONObject): List<PlaceCandidate> {
            val array = json.optJSONArray("results") ?: return emptyList()
            return (0 until minOf(array.length(), 8)).mapNotNull { i ->
                val row = array.optJSONObject(i) ?: return@mapNotNull null
                val lat = row.optDouble("latitude", Double.NaN)
                val lon = row.optDouble("longitude", Double.NaN)
                if (!validCoordinates(lat, lon)) return@mapNotNull null
                val zone = row.optString("timezone").takeIf { runCatching { ZoneId.of(it) }.isSuccess } ?: return@mapNotNull null
                val name = listOf("name", "admin1", "country").map { row.optString(it) }.filter { it.isNotBlank() }
                    .distinct().joinToString(", ").take(300)
                if (name.isBlank()) null else PlaceCandidate(name, lat, lon, zone,
                    row.optDouble("elevation", 0.0).takeIf { it.isFinite() && it in -500.0..9000.0 } ?: 0.0)
            }
        }
    }
}
