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
        assertEquals(12_000, json.getInt("max_output_tokens"))
        assertEquals("medium", json.getJSONObject("reasoning").getString("effort"))
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

    @Test fun outputLimitReportsReasonAndUsageWithoutPartialAnalysisOrPrivateProse() = runBlocking {
        val input = input()
        val guide = NorthGuide.create(input, environment.north, files.newFile("guide.png"))
        var requests = 0
        val fixture = JSONObject(OrientationFixtures.response()).put("status", "incomplete")
            .put("incomplete_details", JSONObject().put("reason", "max_output_tokens").put("message", "private-server-message"))
            .put("usage", JSONObject().put("output_tokens", 4000).put("output_tokens_details", JSONObject().put("reasoning_tokens", 3950)))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .header("x-request-id", "req_fixture123")
                .body(fixture.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val failure = runCatching { OrientationAnalysisClient(client).analyze(input, guide, "local-test-placeholder".toCharArray(), environment) }.exceptionOrNull()
        assertTrue(failure is ImageApiException)
        val message = failure!!.message!!
        assertTrue(message.contains("ANALYSIS_OUTPUT_LIMIT"))
        assertTrue(message.contains("Reason: max_output_tokens"))
        assertTrue(message.contains("Output tokens: 4000"))
        assertTrue(message.contains("Reasoning tokens: 3950"))
        assertTrue(message.contains("Requested output budget: 12000"))
        assertTrue(message.contains("Analysis client request ID:"))
        assertTrue(message.contains("OpenAI analysis request ID: req_fixture123"))
        assertTrue(message.contains("No image generation was requested"))
        assertFalse(message.contains("private-server-message"))
        assertFalse(message.contains("local-test-placeholder"))
        assertEquals(1, requests)
    }

    @Test fun nonCompletedStatusesAreDistinguishedAndArbitraryDiagnosticFieldsAreRedacted() = runBlocking {
        val input = input()
        val guide = NorthGuide.create(input, environment.north, files.newFile("guide.png"))
        val cases = listOf(
            JSONObject(OrientationFixtures.response()).put("status", "incomplete").put("incomplete_details", JSONObject().put("reason", "content_filter")) to "ANALYSIS_CONTENT_FILTER",
            JSONObject(OrientationFixtures.response()).put("status", "failed").put("error", JSONObject().put("code", "server_error").put("message", "private-error-text")) to "ANALYSIS_API_ERROR",
            JSONObject(OrientationFixtures.response()).put("status", "in_progress") to "ANALYSIS_NOT_COMPLETED",
            JSONObject(OrientationFixtures.response()).put("status", "private-status").put("error", JSONObject().put("code", "private-api-code")).put("usage", JSONObject().put("output_tokens", "private-token-count")) to "ANALYSIS_API_ERROR",
            JSONObject(OrientationFixtures.response()).put("status", "incomplete").put("incomplete_details", JSONObject().put("reason", "private-reason")).put("usage", JSONObject().put("output_tokens", -1)) to "ANALYSIS_INCOMPLETE",
        )
        for ((fixture, expectedCode) in cases) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .header("x-request-id", "private-header-text")
                    .body(fixture.toString().toResponseBody("application/json".toMediaType())).build()
            }.build()
            val failure = runCatching { OrientationAnalysisClient(client).analyze(input, guide, "local-test-placeholder".toCharArray(), environment) }.exceptionOrNull()
            assertTrue(failure is ImageApiException)
            assertTrue(failure!!.message!!.contains(expectedCode))
            assertFalse(failure.message!!.contains("private-"))
            assertFalse(failure.message!!.contains("Output tokens: -1"))
        }
    }

    @Test fun incompleteMessageIsNotAcceptedUnderCompletedTopLevelStatus() = runBlocking {
        val input = input()
        val guide = NorthGuide.create(input, environment.north, files.newFile("guide.png"))
        val fixture = JSONObject(OrientationFixtures.response()).apply {
            getJSONArray("output").getJSONObject(1).put("status", "incomplete")
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(fixture.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val failure = runCatching { OrientationAnalysisClient(client).analyze(input, guide, "local-test-placeholder".toCharArray(), environment) }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("ANALYSIS_MESSAGE_INCOMPLETE"))
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

    @Test fun benignSchemaValidVariationsAreNormalizedInsteadOfRejected() {
        val scene = OrientationFixtures.scene().apply {
            getJSONArray("surfaces")
                .put(JSONObject().put("region", "flat roof").put("normal_azimuth_degrees", JSONObject.NULL)
                    .put("normal_elevation_degrees", 90).put("occlusion", "clear"))
                .put(JSONObject().put("region", "right volume").put("normal_azimuth_degrees", 270)
                    .put("normal_elevation_degrees", JSONObject.NULL).put("occlusion", "unknown"))
                .put(JSONObject().put("region", "Long Timber Facade").put("normal_azimuth_degrees", 270)
                    .put("normal_elevation_degrees", 0).put("occlusion", "clear"))
                .put(JSONObject().put("region", " ").put("normal_azimuth_degrees", 0)
                    .put("normal_elevation_degrees", 0).put("occlusion", "clear"))
            getJSONArray("notes").put("").put("  ")
        }
        val result = OrientationAnalysisClient.parseResponse(JSONObject(OrientationFixtures.response(scene)))
        assertEquals(listOf("left glazed gable", "long timber facade", "south-facing roof plane", "flat roof", "right volume"),
            result.surfaces.map { it.region })
        val roof = result.surfaces.single { it.region == "flat roof" }
        assertEquals(0.0, roof.normalAzimuth!!, 0.0)
        assertEquals(90.0, roof.normalElevation!!, 0.0)
        assertTrue(roof.directSun(environment).startsWith("Faces the Sun:"))
        val unpaired = result.surfaces.single { it.region == "right volume" }
        assertNull(unpaired.normalAzimuth)
        assertNull(unpaired.normalElevation)
        assertEquals(90.0, result.surfaces.single { it.region == "long timber facade" }.normalAzimuth!!, 0.0)
        assertEquals(listOf("Surface directions are inferred from perspective, not measured."), result.notes)
    }

    @Test fun unusableResolvedAnalysisReportsSafeDiagnosticWithoutModelText() {
        val scene = OrientationFixtures.scene().put("surfaces", JSONArray().put(JSONObject()
            .put("region", "private-model-response").put("normal_azimuth_degrees", JSONObject.NULL)
            .put("normal_elevation_degrees", JSONObject.NULL).put("occlusion", "unknown")))
        val error = runCatching { OrientationAnalysisClient.parseResponse(JSONObject(OrientationFixtures.response(scene))) }.exceptionOrNull()
        assertTrue(error is ImageApiException)
        assertTrue(error!!.message!!.contains("Diagnostic: ANALYSIS_INVALID_CONTENT."))
        assertTrue(error.message!!.contains("No image generation was requested"))
        assertFalse(error.message!!.contains("private-model-response"))
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
            assertTrue(error!!.message!!.contains("exceeded its 1-second waiting limit"))
            assertTrue(call.get().isCanceled())
            assertEquals(1, requests)
        } finally { release.countDown() }
    }
}
