package com.revitvisualizer.app.openai

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.revitvisualizer.app.imaging.ImageProcessor
import com.revitvisualizer.app.imaging.LocalImage
import com.revitvisualizer.app.advanced.EnvironmentSettings
import com.revitvisualizer.app.advanced.NorthGuide
import com.revitvisualizer.app.advanced.Place
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** All responses are local fixtures intercepted before network access. No real API key or paid call is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpenAiImagesTest {
    @get:Rule val files = TemporaryFolder()

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GRAY)
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            bitmap.recycle()
            it.toByteArray()
        }
    }

    // Initialize Robolectric's native decoder on the test thread before OkHttp callbacks use it.
    private fun input(bytes: ByteArray) = ImageProcessor.requirePng(files.newFile("input.png").apply { writeBytes(bytes) })

    @Test fun sendsDocumentedMultipartAndStoresExactReturnedPng() = runBlocking {
        val bytes = png()
        val source = input(bytes)
        var requests = 0
        var sentRequest: okhttp3.Request? = null
        var sentBody = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            val request = chain.request()
            sentRequest = request
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            sentBody = buffer.readUtf8()
            val json = """{"created":0,"data":[{"b64_json":"${Base64.encodeToString(bytes, Base64.NO_WRAP)}"}]}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = OpenAiImages(client).edit(source, "local-test-placeholder".toCharArray(), files.newFile("result.png"))
        assertEquals("https://api.openai.com/v1/images/edits", sentRequest!!.url.toString())
        assertEquals("POST", sentRequest!!.method)
        assertTrue(sentRequest!!.body!!.isOneShot())
        val boundary = sentRequest!!.body!!.contentType()!!.parameter("boundary")!!
        val parts = sentBody.split("--$boundary")
        listOf("model" to OpenAiImages.MODEL, "n" to "1", "partial_images" to "0",
            "quality" to "max", "size" to "auto", "output_format" to "png", "stream" to "true").forEach { (name, value) ->
            val part = parts.single { it.contains("name=\"$name\"\r\n") }
            assertEquals(value, part.substringAfter("\r\n\r\n").removeSuffix("\r\n"))
        }
        assertTrue(sentBody.contains(ArchitecturePrompt.text))
        assertTrue(sentBody.contains("name=\"image[]\"; filename=\"revit-view.png\""))
        assertEquals(1, parts.count { it.contains("name=\"image[]\"") })
        assertEquals(ArchitecturePrompt.text, parts.single { it.contains("name=\"prompt\"\r\n") }
            .substringAfter("\r\n\r\n").removeSuffix("\r\n"))
        assertFalse(sentBody.contains("input_fidelity"))
        assertEquals("text/event-stream", sentRequest!!.header("Accept"))
        assertArrayEquals(bytes, result.file.readBytes())
        assertArrayEquals(bytes, source.file.readBytes())
        assertEquals(1, requests)
    }

    @Test fun advancedSendsCleanInputThenNorthGuideWithOneOutputAndNoRetry() = runBlocking {
        val bytes = png()
        val source = input(bytes)
        val environment = EnvironmentSettings(place = Place("Bø, Telemark, Norway", 59.41299, 9.0693,
            java.time.ZoneId.of("Europe/Oslo")), northConfirmed = true, snow = true).resolve()
        val guide = NorthGuide.create(source, environment.north, files.newFile("north-guide.png"))
        var requests = 0
        var sentBody = ""
        var boundary = ""
        val paths = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            paths += chain.request().url.encodedPath
            val json = if (chain.request().url.encodedPath == "/v1/responses") OrientationFixtures.response() else {
                val buffer = Buffer()
                chain.request().body!!.writeTo(buffer)
                sentBody = buffer.readUtf8()
                boundary = chain.request().body!!.contentType()!!.parameter("boundary")!!
                """{"data":[{"b64_json":"${Base64.encodeToString(bytes, Base64.NO_WRAP)}"}]}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = OpenAiImages(client).edit(source, "local-test-placeholder".toCharArray(), files.newFile("advanced-result.png"), environment, guide)
        assertEquals(2, Regex("name=\"image\\[\\]\"").findAll(sentBody).count())
        assertTrue(sentBody.indexOf("filename=\"revit-view.png\"") < sentBody.indexOf("filename=\"north-orientation-guide.png\""))
        assertTrue(sentBody.contains("SNOW ON"))
        assertTrue(sentBody.contains("Europe/Oslo"))
        assertTrue(sentBody.contains("SCENE ORIENTATION ANALYSIS"))
        assertTrue(sentBody.contains("left glazed gable"))
        assertTrue(sentBody.contains("Back-facing the Sun: NO direct sunlight"))
        assertEquals("1", sentBody.split("--$boundary").single { it.contains("name=\"n\"\r\n") }
            .substringAfter("\r\n\r\n").removeSuffix("\r\n"))
        assertEquals(listOf("/v1/responses", "/v1/images/edits"), paths.toList())
        assertEquals(2, requests)
        assertArrayEquals(bytes, source.file.readBytes())
        assertArrayEquals(bytes, result.file.readBytes())
    }

    @Test fun partialAdvancedUsesOneImagePostWithoutAnalysisOrGuide() = runBlocking {
        val bytes = png()
        val source = input(bytes)
        val place = Place("Ålesund", 62.47226, 6.1549, java.time.ZoneId.of("Europe/Oslo"))
        val cases = listOf(EnvironmentSettings(snow = true), EnvironmentSettings(place = place),
            EnvironmentSettings(northConfirmed = true), EnvironmentSettings())
        for ((index, settings) in cases.withIndex()) {
            val environment = settings.copy(weather = com.revitvisualizer.app.advanced.Weather.Rain).resolve()
            val paths = CopyOnWriteArrayList<String>()
            var body = ""
            var analysisCalled = false
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                paths += chain.request().url.encodedPath
                body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
                response(chain, """{"data":[{"b64_json":"${Base64.encodeToString(bytes, Base64.NO_WRAP)}"}]}""".toResponseBody("application/json".toMediaType()))
            }.build()
            try {
                val result = OpenAiImages(client).edit(source, "local-test-placeholder".toCharArray(),
                    files.newFile("partial-$index.png"), environment, onOrientationAnalysis = { analysisCalled = true },
                    onProgress = { assertFalse(it == ImageRequestPhase.AnalysingOrientation) })
                assertEquals(listOf("/v1/images/edits"), paths.toList())
                assertFalse(analysisCalled)
                assertEquals(1, Regex("name=\"image\\[\\]\"").findAll(body).count())
                assertFalse(body.contains("north-orientation-guide"))
                assertFalse(body.contains("Solar bearing:"))
                assertTrue(body.contains("Requested weather: Rain"))
                assertTrue(body.contains(if (settings.snow) "SNOW ON" else "SNOW OFF"))
                assertEquals(settings.place != null, body.contains("Ålesund"))
                assertArrayEquals(bytes, source.file.readBytes())
                assertArrayEquals(bytes, result.file.readBytes())
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }

    @Test fun failedOrAmbiguousAnalysisNeverSubmitsAnImageGeneration() = runBlocking {
        val bytes = png()
        val source = input(bytes)
        val environment = EnvironmentSettings(place = Place("Ålesund", 62.47226, 6.1549,
            java.time.ZoneId.of("Europe/Oslo")), northConfirmed = true).resolve()
        val guide = NorthGuide.create(source, environment.north, files.newFile("guide.png"))
        val cases = listOf(503 to "private-server-body", 200 to OrientationFixtures.response(
            OrientationFixtures.scene().put("orientation_status", "ambiguous")),
            200 to org.json.JSONObject(OrientationFixtures.response()).put("status", "incomplete")
                .put("incomplete_details", org.json.JSONObject().put("reason", "max_output_tokens")).toString())
        cases.forEachIndexed { index, (status, json) ->
            val paths = CopyOnWriteArrayList<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                paths += chain.request().url.encodedPath
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                    .body(json.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val destination = java.io.File(files.root, "never-generated-$index.png")
            val failure = runCatching { OpenAiImages(client).edit(source, "local-test-placeholder".toCharArray(), destination, environment, guide) }.exceptionOrNull()
            assertTrue(failure is ImageApiException)
            assertTrue(failure!!.message!!.contains("No image generation was requested"))
            assertFalse(failure.message!!.contains("private-server-body"))
            assertEquals(listOf("/v1/responses"), paths.toList())
            assertFalse(destination.exists())
        }
    }

    @Test fun cancellationAfterAnalysisPreventsTheImagePost() = runBlocking {
        val bytes = png()
        val source = input(bytes)
        val environment = EnvironmentSettings(place = Place("Ålesund", 62.47226, 6.1549,
            java.time.ZoneId.of("Europe/Oslo")), northConfirmed = true).resolve()
        val guide = NorthGuide.create(source, environment.north, files.newFile("guide.png"))
        val paths = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            paths += chain.request().url.encodedPath
            response(chain, OrientationFixtures.response().toResponseBody("application/json".toMediaType()))
        }.build()
        val destination = java.io.File(files.root, "never-generated.png")
        val job = launch {
            val generationJob = coroutineContext[kotlinx.coroutines.Job]!!
            OpenAiImages(client).edit(source, "local-test-placeholder".toCharArray(), destination, environment, guide,
                onOrientationAnalysis = { generationJob.cancel() })
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf("/v1/responses"), paths.toList())
        assertFalse(destination.exists())
    }

    @Test fun errorsDoNotExposeResponseBodiesOrRetryRequests() {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized")
                .body("untrusted-private-response".toResponseBody()).build()
        }.build()
        val error = assertThrows(ImageApiException::class.java) {
            runBlocking { OpenAiImages(client).edit(input(png()), "local-test-placeholder".toCharArray(), files.newFile("failed.png")) }
        }
        assertFalse(error.message!!.contains("untrusted-private-response"))
        assertFalse(error.message!!.contains("local-test-placeholder"))
        assertEquals(1, requests)
    }

    @Test fun invalidImageResponseLeavesNoResultFile() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":[{"b64_json":"bm90LWFuLWltYWdl"}]}""".toResponseBody()).build()
        }.build()
        val output = files.newFile("invalid.png")
        assertThrows(ImageApiException::class.java) {
            runBlocking { OpenAiImages(client).edit(input(png()), "local-test-placeholder".toCharArray(), output) }
        }
        assertFalse(output.exists())
    }

    @Test fun cancellationCancelsTheUnderlyingCall() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            if (chain.call().isCanceled()) cancelled.countDown()
            throw java.io.IOException("Local test cancellation")
        }.build()
        val source = input(png())
        val output = files.newFile("cancelled.png")
        val job = launch(Dispatchers.Default) { OpenAiImages(client).edit(source, "local-test-placeholder".toCharArray(), output) }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            release.countDown()
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        } finally { release.countDown() }
    }

    private fun response(chain: Interceptor.Chain, body: ResponseBody, status: Int = 200) =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(status).message("Local test response").body(body).build()

    private fun finalEvent(bytes: ByteArray) =
        "event: image_edit.completed\ndata: {\"type\":\"image_edit.completed\",\"b64_json\":\"${Base64.encodeToString(bytes, Base64.NO_WRAP)}\"}\n\n"

    private fun eventBody(text: String, onEnd: () -> Unit = {}): ResponseBody = object : ResponseBody() {
        override fun contentType() = "text/event-stream".toMediaType()
        override fun contentLength() = -1L
        private val data = Buffer().writeUtf8(text)
        private val stream = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (data.size == 0L) { onEnd(); return -1L }
                return data.read(sink, minOf(byteCount, 7L))
            }
            override fun timeout() = Timeout.NONE
            override fun close() { data.close() }
        }.buffer()
        override fun source(): BufferedSource = stream
    }

    @Test fun streamedFinalImageDoesNotWaitForServerEof() = runBlocking {
        val bytes = png()
        val release = CountDownLatch(1)
        val call = AtomicReference<Call>()
        val phases = CopyOnWriteArrayList<ImageRequestPhase>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            call.set(chain.call())
            chain.request().body!!.writeTo(Buffer())
            val text = ": keep-alive\r\n\r\n" +
                "event: image_edit.partial_image\ndata: {\"type\":\"image_edit.partial_image\",\"b64_json\":\"not-a-final-image\"}\n\n" +
                finalEvent(bytes)
            response(chain, eventBody(text) { release.await(5, TimeUnit.SECONDS) })
        }.build()
        try {
            val output = withTimeout(3_000) {
                OpenAiImages(client).edit(input(bytes), "local-test-placeholder".toCharArray(), files.newFile("stream.png"), onProgress = phases::add)
            }
            assertArrayEquals(bytes, output.file.readBytes())
            assertTrue(call.get().isCanceled())
            assertEquals(listOf(ImageRequestPhase.Connecting, ImageRequestPhase.Uploading, ImageRequestPhase.AwaitingHeaders,
                ImageRequestPhase.Waiting, ImageRequestPhase.Receiving), phases.distinct())
        } finally { release.countDown() }
    }

    @Test fun acceptsMultilineSseDataWithoutAnEventName() = runBlocking {
        val bytes = png()
        val text = "retry: 1000\nid: local-fixture\ndata: {\ndata: \"type\":\"image_edit.completed\",\n" +
            "data: \"b64_json\":\"${Base64.encodeToString(bytes, Base64.NO_WRAP)}\"\ndata: }\n\n"
        val client = OkHttpClient.Builder().addInterceptor { response(it, eventBody(text)) }.build()
        val result = OpenAiImages(client).edit(input(bytes), "local-test-placeholder".toCharArray(), files.newFile("multiline.png"))
        assertArrayEquals(bytes, result.file.readBytes())
    }

    @Test fun aPartialImageAndClosedConnectionAreNotTreatedAsAFinalResult() {
        val bytes = png()
        val text = "event: image_edit.partial_image\ndata: {\"b64_json\":\"${Base64.encodeToString(bytes, Base64.NO_WRAP)}\"}\n\n"
        val client = OkHttpClient.Builder().addInterceptor { response(it, eventBody(text)) }.build()
        val output = files.newFile("partial.png")
        val error = assertThrows(ImageApiException::class.java) {
            runBlocking { OpenAiImages(client).edit(input(bytes), "local-test-placeholder".toCharArray(), output) }
        }
        assertTrue(error.message!!.contains("INCOMPLETE_STREAM"))
        assertFalse(output.exists())
    }

    @Test fun dnsTlsAndConnectErrorsAreNotReportedAsTimeouts() {
        val source = input(png())
        val failures = listOf(
            UnknownHostException("untrusted-private-dns-detail") to "DNS",
            SSLHandshakeException("untrusted-private-tls-detail") to "TLS",
            ConnectException("untrusted-private-connect-detail") to "CONNECT",
        )
        failures.forEachIndexed { index, (failure, diagnostic) ->
            val client = OkHttpClient.Builder().addInterceptor { throw failure }.build()
            val error = assertThrows(ImageApiException::class.java) {
                runBlocking { OpenAiImages(client).edit(source, "local-test-placeholder".toCharArray(), files.newFile("connection-$index.png")) }
            }
            assertTrue(error.message!!.contains("Diagnostic: $diagnostic."))
            assertTrue(error.message!!.contains("Stage: connecting."))
            assertFalse(error.message!!.contains("timed out"))
            assertFalse(error.message!!.contains("untrusted-private"))
        }
    }

    @Test fun interruptedStreamReportsTheWaitingPhaseInsteadOfInvalidPng() {
        val client = OkHttpClient.Builder().addInterceptor {
            response(it, eventBody(": accepted\n\n") { throw SocketTimeoutException("untrusted-private-timeout-detail") })
        }.build()
        val error = assertThrows(ImageApiException::class.java) {
            runBlocking { OpenAiImages(client).edit(input(png()), "local-test-placeholder".toCharArray(), files.newFile("timeout.png")) }
        }
        assertTrue(error.message!!.contains("SOCKET_TIMEOUT"))
        assertTrue(error.message!!.contains("Stage: waiting for OpenAI."))
        assertFalse(error.message!!.contains("INVALID_IMAGE"))
        assertFalse(error.message!!.contains("untrusted-private"))
    }

    @Test fun gatewayTimeoutIncludesStatusButNeverTheServerBodyAndIsNotRetried() {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            chain.request().body!!.writeTo(Buffer())
            response(chain, "untrusted-private-gateway-detail".toResponseBody(), 504)
        }.build()
        val error = assertThrows(ImageApiException::class.java) {
            runBlocking { OpenAiImages(client).edit(input(png()), "local-test-placeholder".toCharArray(), files.newFile("gateway.png")) }
        }
        assertTrue(error.message!!.contains("HTTP 504"))
        assertTrue(error.message!!.contains("Diagnostic: HTTP_504."))
        assertFalse(error.message!!.contains("untrusted-private"))
        assertEquals(1, requests)
    }

    @Test fun aJsonFallbackReadTimeoutIsNotReportedAsAnInvalidImage() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain, object : ResponseBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = -1L
                override fun source(): BufferedSource = object : Source {
                    override fun read(sink: Buffer, byteCount: Long): Long = throw SocketTimeoutException("untrusted-private-read-detail")
                    override fun timeout() = Timeout.NONE
                    override fun close() = Unit
                }.buffer()
            })
        }.build()
        val error = assertThrows(ImageApiException::class.java) {
            runBlocking { OpenAiImages(client).edit(input(png()), "local-test-placeholder".toCharArray(), files.newFile("json-timeout.png")) }
        }
        assertTrue(error.message!!.contains("SOCKET_TIMEOUT"))
        assertTrue(error.message!!.contains("Stage: receiving the image."))
        assertFalse(error.message!!.contains("INVALID_IMAGE"))
        assertFalse(error.message!!.contains("untrusted-private"))
    }

    @Test fun overallDeadlineStillCancelsAnAlreadyOpenedSseResponse() {
        val release = CountDownLatch(1)
        val call = AtomicReference<Call>()
        val phases = CopyOnWriteArrayList<ImageRequestPhase>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            call.set(chain.call())
            response(chain, eventBody(": waiting\n\n") { release.await(5, TimeUnit.SECONDS) })
        }.build()
        val output = files.newFile("deadline.png")
        try {
            val error = assertThrows(ImageApiException::class.java) {
                runBlocking { OpenAiImages(client, requestTimeoutMillis = 1_500).edit(
                    input(png()), "local-test-placeholder".toCharArray(), output, onProgress = phases::add,
                ) }
            }
            assertTrue(phases.contains(ImageRequestPhase.Waiting))
            assertTrue(error.message!!.contains("REQUEST_DEADLINE"))
            assertTrue(call.get().isCanceled())
            assertFalse(output.exists())
        } finally { release.countDown() }
    }

    @Test fun streamErrorPayloadNeverExposesPrivateServerText() {
        val client = OkHttpClient.Builder().addInterceptor {
            response(it, eventBody("event: error\ndata: {\"message\":\"untrusted-private-stream-detail\"}\n\n"))
        }.build()
        val error = assertThrows(ImageApiException::class.java) {
            runBlocking { OpenAiImages(client).edit(input(png()), "local-test-placeholder".toCharArray(), files.newFile("stream-error.png")) }
        }
        assertTrue(error.message!!.contains("STREAM_ERROR"))
        assertFalse(error.message!!.contains("untrusted-private"))
    }

    @Test fun oversizedResponseFailsBeforeBufferingItsBody() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain, object : ResponseBody() {
                override fun contentType() = "text/event-stream".toMediaType()
                override fun contentLength() = Long.MAX_VALUE
                override fun source(): BufferedSource = Buffer()
            })
        }.build()
        val error = assertThrows(ImageApiException::class.java) {
            runBlocking { OpenAiImages(client).edit(input(png()), "local-test-placeholder".toCharArray(), files.newFile("oversize.png")) }
        }
        assertTrue(error.message!!.contains("RESPONSE_SIZE"))
    }

    @Test fun transportDiagnosticsDistinguishClosedSocketsAndEofWithoutLeakingMessages() {
        val source = input(png())
        listOf(
            java.io.EOFException("untrusted-private-eof") to "RESPONSE_EOF",
            java.net.SocketException("untrusted-private-socket") to "SOCKET_CLOSED",
            java.net.ProtocolException("untrusted-private-protocol") to "HTTP_PROTOCOL",
        ).forEachIndexed { index, (failure, diagnostic) ->
            var clientId: String? = null
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                clientId = chain.request().header("X-Client-Request-Id")
                chain.request().body!!.writeTo(Buffer())
                throw failure
            }.build()
            val error = assertThrows(ImageApiException::class.java) {
                runBlocking { OpenAiImages(client).edit(source, "local-test-placeholder".toCharArray(), files.newFile("transport-$index.png")) }
            }
            assertTrue(error.message!!.contains("Diagnostic: $diagnostic."))
            assertTrue(error.message!!.contains("Stage: waiting for response headers."))
            assertTrue(error.message!!.contains("Response: no response headers."))
            assertTrue(error.message!!.contains("Client request ID: $clientId."))
            assertEquals(clientId, java.util.UUID.fromString(clientId).toString())
            assertFalse(error.message!!.contains("untrusted-private"))
            assertFalse(error.message!!.contains("local-test-placeholder"))
        }
    }

    @Test fun openedStreamIncludesOnlySafeResponseMetadataAndBytesReceived() {
        val bytes = ": accepted\n\n"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain, eventBody(bytes) { throw java.net.SocketException("untrusted-private-socket") })
                .newBuilder().header("x-request-id", "req_local_fixture_123")
                .header("unrelated-header", "untrusted-private-header").build()
        }.build()
        val error = assertThrows(ImageApiException::class.java) {
            runBlocking { OpenAiImages(client).edit(input(png()), "local-test-placeholder".toCharArray(), files.newFile("metadata.png")) }
        }
        assertTrue(error.message!!.contains("Diagnostic: SOCKET_CLOSED."))
        assertTrue(error.message!!.contains("Connection: http/1.1. Response: HTTP 200. Read: ${bytes.length} bytes."))
        assertTrue(error.message!!.contains("OpenAI request ID: req_local_fixture_123."))
        assertFalse(error.message!!.contains("untrusted-private"))
        assertFalse(error.message!!.contains("local-test-placeholder"))
    }

    @Test fun arbitraryRequestIdHeaderIsNotCopiedIntoTheError() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain, "untrusted-private-body".toResponseBody(), 504)
                .newBuilder().header("x-request-id", "untrusted-private-header").build()
        }.build()
        val error = assertThrows(ImageApiException::class.java) {
            runBlocking { OpenAiImages(client).edit(input(png()), "local-test-placeholder".toCharArray(), files.newFile("header.png")) }
        }
        assertFalse(error.message!!.contains("OpenAI request ID:"))
        assertFalse(error.message!!.contains("untrusted-private"))
    }
}
