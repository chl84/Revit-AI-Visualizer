package com.revitvisualizer.app.openai

import android.util.Base64
import android.util.Base64InputStream
import android.util.JsonReader
import com.revitvisualizer.app.imaging.ImageProcessor
import com.revitvisualizer.app.imaging.LocalImage
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okio.BufferedSink

class ImageApiException(message: String) : IOException(message)

class OpenAiImages(private val client: OkHttpClient = secureClient()) {
    suspend fun edit(input: LocalImage, credential: CharArray, destination: File): LocalImage {
        if (credential.isEmpty() || credential.any { it.code !in 33..126 }) {
            throw ImageApiException("Enter a valid API key without spaces or line breaks.")
        }
        if (input.file.length() >= 50_000_000L) {
            throw ImageApiException("The prepared PNG exceeds the API's 50 MB input limit. Select a smaller area or import a smaller image.")
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL)
            .addFormDataPart("prompt", ArchitecturePrompt.text)
            .addFormDataPart("n", "1")
            .addFormDataPart("partial_images", "0")
            .addFormDataPart("quality", "max")
            .addFormDataPart("size", "auto")
            .addFormDataPart("output_format", "png")
            .addFormDataPart("image[]", "revit-view.png", input.file.asRequestBody("image/png".toMediaType()))
            .build()
        // A one-shot body also prevents HTTP follow-ups such as a 503 with Retry-After: 0.
        val oneShotBody = object : RequestBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
        }
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer ${String(credential)}")
            .post(oneShotBody)
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    destination.delete()
                    if (continuation.isActive) continuation.resumeWithException(
                        ImageApiException("The OpenAI connection failed or timed out. Check your connection before trying again. The request is not retried automatically."),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!it.isSuccessful) throw ImageApiException(messageForStatus(it.code))
                            val stream = it.body?.byteStream()
                                ?: throw ImageApiException("OpenAI returned an empty response.")
                            readImage(stream, destination)
                        }
                        continuation.resume(result, onCancellation = { _, value, _ -> value.file.delete() })
                    } catch (error: Exception) {
                        destination.delete()
                        if (continuation.isActive) continuation.resumeWithException(
                            if (error is ImageApiException) error
                            else ImageApiException("OpenAI returned an incomplete or invalid image. No result was saved."),
                        )
                    } finally {
                        if (continuation.isCancelled) destination.delete()
                    }
                }
            })
        }
    }

    private fun readImage(input: InputStream, destination: File): LocalImage {
        var encoded: String? = null
        var count = 0
        JsonReader(LimitedInput(input, 96L * 1024 * 1024).reader(Charsets.UTF_8).buffered()).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "data") {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        count++
                        reader.beginObject()
                        while (reader.hasNext()) {
                            if (reader.nextName() == "b64_json" && count == 1) encoded = reader.nextString()
                            else reader.skipValue()
                        }
                        reader.endObject()
                    }
                    reader.endArray()
                } else reader.skipValue()
            }
            reader.endObject()
        }
        if (count != 1 || encoded.isNullOrEmpty()) throw ImageApiException("OpenAI did not return one complete image.")
        // Decode directly to disk without allocating another complete image byte array.
        Base64InputStream(StringInput(encoded!!), Base64.DEFAULT).use { decoder ->
            destination.outputStream().use { output ->
                LimitedInput(decoder, 64L * 1024 * 1024).copyTo(output)
            }
        }
        return ImageProcessor.requirePng(destination)
    }

    companion object {
        const val MODEL = "gpt-image-2.6-sunburst"
        private const val ENDPOINT = "https://api.openai.com/v1/images/edits"

        private fun secureClient() = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.MINUTES)
            .readTimeout(15, TimeUnit.MINUTES)
            .callTimeout(20, TimeUnit.MINUTES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

        private fun messageForStatus(code: Int): String = when (code) {
            400, 413, 422 -> "OpenAI rejected this image request. Check the input size and your project's support for GPT Image 2.6 Sunburst."
            401 -> "OpenAI rejected the API key. Clear it and enter a valid key."
            403, 404 -> "Your OpenAI project does not have access to GPT Image 2.6 Sunburst, or the model is unavailable."
            429 -> "OpenAI reported a rate or billing limit. Check your account before trying again."
            in 500..599 -> "OpenAI is temporarily unavailable. Try again later."
            else -> "OpenAI could not complete the request. No automatic retry was made."
        }
    }
}

private class StringInput(private val value: String) : InputStream() {
    private var position = 0
    override fun read(): Int = if (position < value.length) value[position++].code else -1
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position == value.length) return -1
        val count = minOf(length, value.length - position)
        repeat(count) { buffer[offset + it] = value[position++].code.toByte() }
        return count
    }
}

private class LimitedInput(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var total = 0L
    override fun read(): Int = super.read().also { if (it >= 0) checkLimit(1) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        `in`.read(buffer, offset, length).also { if (it > 0) checkLimit(it) }
    private fun checkLimit(count: Int) {
        total += count
        if (total > limit) throw IOException("Image response exceeded the local size limit.")
    }
}
