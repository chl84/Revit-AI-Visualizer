package com.revitvisualizer.app.openai

import com.revitvisualizer.app.imaging.LocalImage
import com.revitvisualizer.app.advanced.EnvironmentPrompt
import com.revitvisualizer.app.advanced.ResolvedEnvironment
import com.revitvisualizer.app.advanced.SceneOrientation
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okio.BufferedSink

class ImageApiException(message: String) : IOException(message)

enum class ImageRequestPhase(val description: String, val diagnostic: String) {
    AnalysingOrientation("Analysing the north arrow and scene orientation…", "analysing orientation"),
    Connecting("Connecting securely to OpenAI…", "connecting"),
    Uploading("Uploading the prepared image…", "uploading"),
    AwaitingHeaders("Waiting for OpenAI to respond…", "waiting for response headers"),
    Waiting("Waiting for the final image from OpenAI…", "waiting for OpenAI"),
    Receiving("Receiving and saving the final PNG…", "receiving the image"),
}

class OpenAiImages(
    client: OkHttpClient = secureClient(),
    private val requestTimeoutMillis: Long = TimeUnit.MINUTES.toMillis(20),
    private val orientationClient: OrientationAnalysisClient = OrientationAnalysisClient(client),
) {
    // SSE exits OkHttp's full-call timeout on response headers. The coroutine deadline
    // below bounds the entire operation while permitting long waits between events.
    private val events = EventSources.createFactory(client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .apply { interceptors().add(0, ImageResponseReader.limitResponseSize) }
        .addNetworkInterceptor { chain ->
            chain.request().tag(ImageRequestTrace::class.java)?.connected(chain.connection()?.protocol())
            chain.proceed(chain.request())
        }
        .build())

    suspend fun edit(
        input: LocalImage,
        credential: CharArray,
        destination: File,
        environment: ResolvedEnvironment? = null,
        northGuide: LocalImage? = null,
        onOrientationAnalysis: (SceneOrientation) -> Unit = {},
        onProgress: (ImageRequestPhase) -> Unit = {},
    ): LocalImage {
        if (credential.isEmpty() || credential.any { it.code !in 33..126 }) {
            throw ImageApiException("Enter a valid API key without spaces or line breaks.")
        }
        if (input.file.canonicalFile == destination.canonicalFile) {
            throw ImageApiException("The original image must not be overwritten.")
        }
        if (input.file.length() >= 50_000_000L) {
            throw ImageApiException("The prepared PNG exceeds the API's 50 MB input limit. Select a smaller area or import a smaller image.")
        }
        require(requestTimeoutMillis > 0)
        require((environment == null) == (northGuide == null)) { "Advanced settings require an orientation guide." }
        if (northGuide != null && (northGuide.file.length() >= 50_000_000L || northGuide.file.canonicalFile == destination.canonicalFile)) {
            throw ImageApiException("The north arrow guide could not be prepared safely.")
        }
        val orientation = if (environment != null) {
            onProgress(ImageRequestPhase.AnalysingOrientation)
            orientationClient.analyze(input, northGuide!!, credential, environment).also(onOrientationAnalysis)
        } else null
        currentCoroutineContext().ensureActive()
        val trace = ImageRequestTrace(onProgress)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL)
            .addFormDataPart("prompt", EnvironmentPrompt.build(environment, orientation))
            .addFormDataPart("n", "1")
            .addFormDataPart("partial_images", "0")
            .addFormDataPart("stream", "true")
            .addFormDataPart("quality", "max")
            .addFormDataPart("size", "auto")
            .addFormDataPart("output_format", "png")
            .addFormDataPart("image[]", "revit-view.png", input.file.asRequestBody("image/png".toMediaType()))
            .apply { northGuide?.let { addFormDataPart("image[]", "north-orientation-guide.png", it.file.asRequestBody("image/png".toMediaType())) } }
            .build()
        val oneShotBody = object : RequestBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                trace.advance(ImageRequestPhase.Uploading)
                body.writeTo(sink)
                sink.flush()
                trace.advance(ImageRequestPhase.AwaitingHeaders)
            }
        }
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer ${String(credential)}")
            .header("Accept", "text/event-stream")
            .header("X-Client-Request-Id", trace.clientRequestId)
            .tag(ImageRequestTrace::class.java, trace)
            .post(oneShotBody)
            .build()
        trace.advance(ImageRequestPhase.Connecting)
        return withTimeoutOrNull(requestTimeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                fun fail(source: EventSource, error: ImageApiException) {
                    source.cancel()
                    if (continuation.isActive) {
                        destination.delete()
                        continuation.resumeWithException(error)
                    }
                }
                fun finish(source: EventSource, result: LocalImage) {
                    // A final image is terminal: never wait for the server to close the stream.
                    source.cancel()
                    continuation.resume(result, onCancellation = { _, image, _ -> image.file.delete() })
                }
                fun readFinal(source: EventSource, read: () -> LocalImage?) {
                    if (!continuation.isActive) { source.cancel(); return }
                    try {
                        read()?.let { finish(source, it) }
                    } catch (_: ImageResponseLimitException) {
                        fail(source, trace.error("RESPONSE_SIZE", "The image response exceeded the device's safe size limit."))
                    } catch (_: ImageStreamException) {
                        fail(source, trace.error("STREAM_ERROR", "OpenAI stopped the image request before completion."))
                    } catch (error: ImageBodyReadException) {
                        fail(source, trace.networkFailure(error))
                    } catch (error: SSLException) {
                        fail(source, trace.networkFailure(error))
                    } catch (_: OutOfMemoryError) {
                        fail(source, trace.error("MEMORY_LIMIT", "There is not enough memory to receive this image."))
                    } catch (_: Exception) {
                        fail(source, trace.error("INVALID_IMAGE", "The final image could not be decoded or saved. Check available storage."))
                    } finally {
                        if (continuation.isCancelled) destination.delete()
                    }
                }
                val source = events.newEventSource(request, object : EventSourceListener() {
                    override fun onOpen(eventSource: EventSource, response: Response) {
                        if (continuation.isActive) trace.advance(ImageRequestPhase.Waiting)
                        else eventSource.cancel()
                    }

                    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                        if (type == "error") {
                            fail(eventSource, trace.error("STREAM_ERROR", "OpenAI stopped the image request before completion."))
                            return
                        }
                        readFinal(eventSource) {
                            ImageResponseReader.event(type, data, destination) {
                                trace.advance(ImageRequestPhase.Receiving)
                            }
                        }
                    }

                    override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                        if (!continuation.isActive) return
                        if (response != null && !response.isSuccessful) {
                            fail(eventSource, trace.error("HTTP_${response.code}", messageForStatus(response.code)))
                        } else if (t is IllegalStateException && response?.isSuccessful == true &&
                            response.body?.contentType()?.subtype == "json") {
                            // Accept a final JSON image if an upstream returns the non-streaming format.
                            readFinal(eventSource) {
                                trace.advance(ImageRequestPhase.Receiving)
                                ImageResponseReader.json(response.body!!.byteStream(), destination)
                            }
                        } else fail(eventSource, trace.networkFailure(t))
                    }

                    override fun onClosed(eventSource: EventSource) {
                        if (continuation.isActive) fail(eventSource, trace.error(
                            "INCOMPLETE_STREAM", "The connection closed before OpenAI returned a complete image.",
                        ))
                    }
                })
                continuation.invokeOnCancellation { source.cancel(); destination.delete() }
            }
        } ?: throw trace.error("REQUEST_DEADLINE", "The image request exceeded its overall waiting limit.")
    }

    companion object {
        const val MODEL = "gpt-image-2.6-sunburst"
        private const val ENDPOINT = "https://api.openai.com/v1/images/edits"

        internal fun secureClient() = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.MINUTES)
            .callTimeout(20, TimeUnit.MINUTES)
            // Keep a quiet HTTP/2 transport active without submitting another image request.
            .pingInterval(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

        private fun messageForStatus(code: Int): String = when (code) {
            400, 413, 422 -> "OpenAI rejected this image request. Check the input size and your project's support for GPT Image 2.6 Sunburst."
            401 -> "OpenAI rejected the API key. Clear it and enter a valid key."
            403, 404 -> "Your OpenAI project does not have access to GPT Image 2.6 Sunburst, or the model is unavailable."
            408, 504 -> "OpenAI or an upstream gateway reported a request timeout (HTTP $code)."
            429 -> "OpenAI reported a rate or billing limit. Check your account before trying again."
            in 500..599 -> "OpenAI is temporarily unavailable (HTTP $code). Try again later."
            else -> "OpenAI could not complete the request (HTTP $code)."
        }
    }
}
