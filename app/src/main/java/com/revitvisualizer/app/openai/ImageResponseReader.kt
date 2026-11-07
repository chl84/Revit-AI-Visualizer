package com.revitvisualizer.app.openai

import android.util.Base64
import android.util.Base64InputStream
import android.util.JsonReader
import android.util.JsonToken
import com.revitvisualizer.app.imaging.ImageProcessor
import com.revitvisualizer.app.imaging.LocalImage
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import okhttp3.Interceptor
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

internal class ImageResponseLimitException : IOException("Image response exceeded the local size limit.")
internal class ImageStreamException : IOException("The image stream reported an error.")
internal class ImageBodyReadException(cause: IOException) : IOException("The response body could not be read.", cause)

internal object ImageResponseReader {
    private const val MAX_RESPONSE_BYTES = 96L * 1024 * 1024

    val limitResponseSize = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val trace = chain.request().tag(ImageRequestTrace::class.java)
        trace?.responseHeaders(response)
        val body = response.body
        if (body == null) response else {
            if (body.contentLength() > MAX_RESPONSE_BYTES) {
                response.close()
                throw ImageResponseLimitException()
            }
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                private val limited = object : ForwardingSource(body.source()) {
                    private var total = 0L
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        val count = try {
                            super.read(sink, byteCount)
                        } catch (error: IOException) {
                            throw ImageBodyReadException(error)
                        }
                        if (count > 0) total += count
                        trace?.received(count)
                        if (total > MAX_RESPONSE_BYTES) throw ImageResponseLimitException()
                        return count
                    }
                }.buffer()
                override fun source() = limited
            }).build()
        }
    }

    fun event(type: String?, data: String, destination: File, onImage: () -> Unit): LocalImage? {
        if (type != null && type != "message" && type != "image_edit.completed") return null
        require(data.length.toLong() <= MAX_RESPONSE_BYTES)
        var payloadType: String? = null
        var encoded: String? = null
        var failed = false
        JsonReader(StringReader(data)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "type" -> payloadType = reader.nextString()
                    "b64_json" -> encoded = reader.nextString()
                    "error" -> { failed = true; reader.skipValue() }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT)
        }
        if (failed || payloadType == "error") throw ImageStreamException()
        if (payloadType != null && payloadType != "image_edit.completed") return null
        if (payloadType == null && type != "image_edit.completed") return null
        onImage()
        return decode(encoded, destination)
    }

    fun json(input: InputStream, destination: File): LocalImage {
        var encoded: String? = null
        var count = 0
        JsonReader(input.reader(Charsets.UTF_8).buffered()).use { reader ->
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
        require(count == 1)
        return decode(encoded, destination)
    }

    private fun decode(encoded: String?, destination: File): LocalImage {
        require(!encoded.isNullOrEmpty())
        // Preserve PNG bytes without allocating another complete decoded-image byte array.
        Base64InputStream(StringInput(encoded), Base64.DEFAULT).use { decoder ->
            destination.outputStream().use { output -> LimitedInput(decoder, 64L * 1024 * 1024).copyTo(output) }
        }
        return ImageProcessor.requirePng(destination)
    }
}

private class StringInput(private val value: String) : InputStream() {
    private var position = 0
    override fun read(): Int = if (position < value.length) nextAscii() else -1
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position == value.length) return -1
        val count = minOf(length, value.length - position)
        repeat(count) { buffer[offset + it] = nextAscii().toByte() }
        return count
    }
    private fun nextAscii(): Int = value[position++].code.also {
        if (it > 127) throw IOException("Invalid base64 character.")
    }
}

private class LimitedInput(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var total = 0L
    override fun read(): Int = super.read().also { if (it >= 0) checkLimit(1) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        `in`.read(buffer, offset, length).also { if (it > 0) checkLimit(it) }
    private fun checkLimit(count: Int) {
        total += count
        if (total > limit) throw ImageResponseLimitException()
    }
}
