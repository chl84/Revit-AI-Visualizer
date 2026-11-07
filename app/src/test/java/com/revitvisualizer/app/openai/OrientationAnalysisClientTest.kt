package com.revitvisualizer.app.openai

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.revitvisualizer.app.advanced.EnvironmentSettings
import com.revitvisualizer.app.advanced.NorthGuide
import com.revitvisualizer.app.advanced.Place
import com.revitvisualizer.app.imaging.ImageProcessor
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrientationAnalysisClientTest {
    @get:Rule val files = TemporaryFolder()
    private val environment get() = EnvironmentSettings(place = Place("Ålesund", 62.47226, 6.1549,
        java.time.ZoneId.of("Europe/Oslo")), northConfirmed = true).resolve()

    private fun input(width: Int = 64, height: Int = 64): com.revitvisualizer.app.imaging.LocalImage {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GRAY)
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        return ImageProcessor.requirePng(files.newFile("clean.png").apply { writeBytes(bytes) })
    }

    @Test fun sendsTwoSampledImagesAndStrictTextSchemaWithoutToolsOrStorage() = runBlocking {
        val input = input(3200, 1600)
        val original = input.file.readBytes()
        val guide = NorthGuide.create(input, environment.north, files.newFile("guide.png"))
        var captured: okhttp3.Request? = null
        var body = ""
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            captured = chain.request()
            body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(OrientationFixtures.response().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = OrientationAnalysisClient(client).analyze(input, guide, "local-test-placeholder".toCharArray(), environment)
        assertTrue(result.resolved)
        assertEquals("https://api.openai.com/v1/responses", captured!!.url.toString())
        assertTrue(captured!!.body!!.isOneShot())
        val json = JSONObject(body)
        assertEquals("gpt-5.4-mini", json.getString("model"))
        assertFalse(json.getBoolean("store"))
        assertFalse(json.getBoolean("stream"))
        assertFalse(json.has("tools"))
        assertFalse(json.has("previous_response_id"))
        assertFalse(body.contains("local-test-placeholder"))
        val format = json.getJSONObject("text").getJSONObject("format")
        assertEquals("json_schema", format.getString("type"))
        assertTrue(format.getBoolean("strict"))
        assertFalse(format.getJSONObject("schema").getBoolean("additionalProperties"))
        val content = json.getJSONArray("input").getJSONObject(0).getJSONArray("content")
        assertEquals(3, content.length())
        assertTrue(content.getJSONObject(0).getString("text").contains("Solar bearing:"))
        val cleanBytes = Base64.decode(content.getJSONObject(1).getString("image_url").substringAfter(','), Base64.DEFAULT)
        val sampled = ImageProcessor.requirePng(files.newFile("sampled.png").apply { writeBytes(cleanBytes) })
        assertEquals(1600, sampled.width)
        assertEquals(800, sampled.height)
        val guideBytes = Base64.decode(content.getJSONObject(2).getString("image_url").substringAfter(','), Base64.DEFAULT)
        assertArrayEquals(guide.file.readBytes(), guideBytes)
        assertEquals("high", content.getJSONObject(1).getString("detail"))
        assertEquals("high", content.getJSONObject(2).getString("detail"))
        assertArrayEquals(original, input.file.readBytes())
        assertEquals(1, requests)
    }

    @Test fun rejectsIncompleteRefusedAmbiguousOrInvalidAnalysisWithoutExposingRawOutput() = runBlocking {
        val input = input()
        val guide = NorthGuide.create(input, environment.north, files.newFile("guide.png"))
        val incomplete = JSONObject(OrientationFixtures.response()).put("status", "incomplete").toString()
        val refused = JSONObject(OrientationFixtures.response()).apply {
            getJSONArray("output").getJSONObject(1).put("content", JSONArray().put(JSONObject()
                .put("type", "refusal").put("refusal", "private-model-response")))
        }.toString()
        val malformedNormal = OrientationFixtures.response(OrientationFixtures.scene().apply {
            getJSONArray("surfaces").getJSONObject(0).put("normal_azimuth_degrees", "private-model-response")
        })
        val cases = listOf(incomplete, refused, "private-model-response", malformedNormal,
            OrientationFixtures.response(OrientationFixtures.scene().put("orientation_status", "ambiguous")))
        for (fixture in cases) {
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body(fixture.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val error = runCatching { OrientationAnalysisClient(client).analyze(input, guide, "local-test-placeholder".toCharArray(), environment) }.exceptionOrNull()
            assertTrue(error is ImageApiException)
            assertTrue(error!!.message!!.contains("No image generation was requested"))
            assertFalse(error.message!!.contains("private-model-response"))
            assertFalse(error.message!!.contains("local-test-placeholder"))
            assertEquals(1, requests)
        }
    }

    @Test fun responseAndGuideSizeLimitsAreEnforced() = runBlocking {
        val input = input()
        val guide = NorthGuide.create(input, environment.north, files.newFile("guide.png"))
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body("x".repeat(256 * 1024 + 1).toResponseBody()).build()
        }.build()
        val error = runCatching { OrientationAnalysisClient(client).analyze(input, guide, "local-test-placeholder".toCharArray(), environment) }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("size limit"))
        assertEquals(1, requests)
        java.io.RandomAccessFile(guide.file, "rw").use { it.setLength(12L * 1024 * 1024 + 1) }
        val tooLarge = runCatching { OrientationAnalysisClient(client).analyze(input, guide, "local-test-placeholder".toCharArray(), environment) }.exceptionOrNull()
        assertTrue(tooLarge!!.message!!.contains("size limit"))
        assertEquals(1, requests)
    }

    @Test fun cancellationClosesAnalysisHttpCall() = runBlocking {
        val input = input()
        val guide = NorthGuide.create(input, environment.north, files.newFile("guide.png"))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val call = AtomicReference<Call>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            call.set(chain.call())
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(OrientationFixtures.response().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val job = launch(Dispatchers.Default) { OrientationAnalysisClient(client).analyze(input, guide, "local-test-placeholder".toCharArray(), environment) }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(call.get().isCanceled())
        } finally { release.countDown() }
    }

    @Test fun analysisDeadlineCancelsRatherThanRetrying() = runBlocking {
        val input = input()
        val guide = NorthGuide.create(input, environment.north, files.newFile("guide.png"))
        val release = CountDownLatch(1)
        val call = AtomicReference<Call>()
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            call.set(chain.call())
            release.await(5, TimeUnit.SECONDS)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(OrientationFixtures.response().toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val error = runCatching { OrientationAnalysisClient(client, timeoutMillis = 1000).analyze(input, guide, "local-test-placeholder".toCharArray(), environment) }.exceptionOrNull()
            assertTrue(error is ImageApiException)
            assertTrue(call.get().isCanceled())
            assertEquals(1, requests)
        } finally { release.countDown() }
    }
}
