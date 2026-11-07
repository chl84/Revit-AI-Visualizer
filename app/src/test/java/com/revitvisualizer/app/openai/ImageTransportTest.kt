package com.revitvisualizer.app.openai

import android.graphics.Bitmap
import android.util.Base64
import com.revitvisualizer.app.imaging.ImageProcessor
import com.revitvisualizer.app.imaging.LocalImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real loopback TLS sockets, synthetic PNGs, and placeholder credentials only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageTransportTest {
    @get:Rule val files = TemporaryFolder()

    private val certificate = HeldCertificate.Builder().commonName("localhost")
        .addSubjectAlternativeName("localhost").build()
    private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()

    private fun input(): LocalImage {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            bitmap.recycle()
            it.toByteArray()
        }
        // Initialize Robolectric's native decoder on its test thread before concurrent callbacks.
        return ImageProcessor.requirePng(files.newFile("input.png").apply { writeBytes(bytes) })
    }

    private fun server(protocol: Protocol): MockWebServer = MockWebServer().apply {
        useHttps(serverTls.sslSocketFactory(), false)
        protocols = if (protocol == Protocol.HTTP_2) listOf(protocol, Protocol.HTTP_1_1) else listOf(protocol)
        start()
    }

    private fun client(server: MockWebServer, protocol: AtomicReference<Protocol> = AtomicReference()): OkHttpClient =
        OpenAiImages.secureClient().newBuilder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            // Only the test rewrites the fixed production URL to a trusted loopback TLS server.
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/v1/images/edits")).build())
            }
            .addNetworkInterceptor { chain ->
                protocol.set(chain.connection()!!.protocol())
                chain.proceed(chain.request())
            }.build()

    private fun OkHttpClient.closeTestConnections() {
        connectionPool.evictAll()
        dispatcher.executorService.shutdownNow()
    }

    @Test(timeout = 120_000) fun receivesFinalImageAfterSeventyQuietSecondsBeforeAndAfterHeadersOverBothProtocols() = runBlocking {
        val source = input()
        val bytes = source.file.readBytes()
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val event = "event: image_edit.completed\ndata: {\"type\":\"image_edit.completed\",\"b64_json\":\"$encoded\"}\n\n"
        val cases = listOf(Protocol.HTTP_1_1, Protocol.HTTP_2).flatMap { protocol ->
            listOf(true, false).map { delayHeaders -> protocol to delayHeaders }
        }
        cases.mapIndexed { index, (expectedProtocol, delayHeaders) -> async(Dispatchers.IO) {
            server(expectedProtocol).use { server ->
                val response = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(event)
                if (delayHeaders) response.setHeadersDelay(70, TimeUnit.SECONDS)
                else response.setBodyDelay(70, TimeUnit.SECONDS)
                server.enqueue(response)
                val protocol = AtomicReference<Protocol>()
                val client = client(server, protocol)
                try {
                    val phases = CopyOnWriteArrayList<ImageRequestPhase>()
                    val started = System.nanoTime()
                    val result = OpenAiImages(client, requestTimeoutMillis = 100_000).edit(
                        source, "local-test-placeholder".toCharArray(), files.newFile("delayed-$index.png"), onProgress = phases::add,
                    )
                    assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) >= 70)
                    assertArrayEquals(bytes, result.file.readBytes())
                    assertEquals(expectedProtocol, protocol.get())
                    assertTrue(phases.contains(ImageRequestPhase.AwaitingHeaders))
                    assertTrue(phases.contains(ImageRequestPhase.Waiting))
                    assertEquals(ImageRequestPhase.Receiving, phases.last())
                    assertEquals(1, server.requestCount)
                    val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                    assertEquals("POST", request.method)
                    assertEquals("/v1/images/edits", request.path)
                } finally { client.closeTestConnections() }
            }
        } }.awaitAll()
        assertArrayEquals(bytes, source.file.readBytes())
        Unit
    }

    @Test fun aServerDisconnectAfterUploadDoesNotSubmitTheImageTwice() {
        server(Protocol.HTTP_1_1).use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val client = client(server)
            val source = input()
            val output = files.newFile("disconnected.png")
            try {
                val error = assertThrows(ImageApiException::class.java) {
                    runBlocking { OpenAiImages(client).edit(source, "local-test-placeholder".toCharArray(), output) }
                }
                assertTrue(error.message!!, error.message!!.contains("Diagnostic: RESPONSE_EOF."))
                assertTrue(error.message!!.contains("Stage: waiting for response headers."))
                assertTrue(error.message!!.contains("Connection: http/1.1. Response: no response headers."))
                assertEquals(1, server.requestCount)
                assertFalse(output.exists())
            } finally { client.closeTestConnections() }
        }
    }

    @Test fun anHttp2StreamResetIsReportedWithoutFallbackOrResubmission() {
        server(Protocol.HTTP_2).use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START).setHttp2ErrorCode(8))
            val client = client(server)
            val source = input()
            val output = files.newFile("reset.png")
            try {
                val error = assertThrows(ImageApiException::class.java) {
                    runBlocking { OpenAiImages(client).edit(source, "local-test-placeholder".toCharArray(), output) }
                }
                assertTrue(error.message!!, error.message!!.contains("Diagnostic: HTTP2_STREAM_RESET."))
                assertTrue(error.message!!.contains("Connection: h2."))
                assertEquals(1, server.requestCount)
                assertFalse(output.exists())
            } finally { client.closeTestConnections() }
        }
    }

    @Test fun aTruncatedJsonResponseIsAConnectionFailureRatherThanAnInvalidPng() {
        server(Protocol.HTTP_1_1).use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"data\":[{\"b64_json\":\"" + "A".repeat(8_192))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val client = client(server)
            val source = input()
            val output = files.newFile("truncated.png")
            try {
                val error = assertThrows(ImageApiException::class.java) {
                    runBlocking { OpenAiImages(client).edit(source, "local-test-placeholder".toCharArray(), output) }
                }
                assertTrue(error.message!!, error.message!!.contains("Diagnostic: HTTP_PROTOCOL."))
                assertTrue(error.message!!.contains("Stage: receiving the image."))
                assertTrue(error.message!!.contains("Response: HTTP 200."))
                assertFalse(error.message!!.contains("INVALID_IMAGE"))
                assertEquals(1, server.requestCount)
                assertFalse(output.exists())
            } finally { client.closeTestConnections() }
        }
    }
}
