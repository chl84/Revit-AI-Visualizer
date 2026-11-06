package com.revitvisualizer.app.openai

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.revitvisualizer.app.imaging.LocalImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
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

    private fun input(bytes: ByteArray) = LocalImage(files.newFile("input.png").apply { writeBytes(bytes) }, 64, 64)

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
            "quality" to "max", "size" to "auto", "output_format" to "png").forEach { (name, value) ->
            val part = parts.single { it.contains("name=\"$name\"\r\n") }
            assertEquals(value, part.substringAfter("\r\n\r\n").removeSuffix("\r\n"))
        }
        assertTrue(sentBody.contains(ArchitecturePrompt.text))
        assertTrue(sentBody.contains("name=\"image[]\"; filename=\"revit-view.png\""))
        assertFalse(sentBody.contains("input_fidelity"))
        assertArrayEquals(bytes, result.file.readBytes())
        assertArrayEquals(bytes, source.file.readBytes())
        assertEquals(1, requests)
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
}
