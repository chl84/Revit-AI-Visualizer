package com.revitvisualizer.app.openai

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import okhttp3.Protocol
import okhttp3.Response

/** Only fixed descriptions, counters, and request identifiers may enter the user-visible trace. */
internal class ImageRequestTrace(private val onProgress: (ImageRequestPhase) -> Unit) {
    val clientRequestId: String = UUID.randomUUID().toString()
    private val started = System.nanoTime()
    private val bytes = AtomicLong()
    @Volatile private var phase = ImageRequestPhase.Connecting
    @Volatile private var protocol: Protocol? = null
    @Volatile private var status: Int? = null
    @Volatile private var serverRequestId: String? = null

    fun advance(next: ImageRequestPhase) {
        if (next.ordinal < phase.ordinal) return
        phase = next
        onProgress(next)
    }

    fun connected(value: Protocol?) { protocol = value }

    fun responseHeaders(response: Response) {
        protocol = response.protocol
        status = response.code
        // Ignore arbitrary header contents rather than copying untrusted text into an error.
        serverRequestId = response.header("x-request-id")?.takeIf {
            it.matches(Regex("req_[A-Za-z0-9_-]{1,96}"))
        }
    }

    fun received(count: Long) { if (count > 0) bytes.addAndGet(count) }

    fun networkFailure(failure: Throwable?): ImageApiException {
        // OkHttp can wrap EOF while reading response headers in a plain IOException.
        val cause = generateSequence(failure) { it.cause }.take(8).firstOrNull {
            it is ImageResponseLimitException || it is UnknownHostException || it is SSLException ||
                it is InterruptedIOException || it is ConnectException || it is EOFException ||
                it is SocketException || it is ProtocolException ||
                it.javaClass.name == "okhttp3.internal.http2.StreamResetException" ||
                it.javaClass.name == "okhttp3.internal.http2.ConnectionShutdownException"
        } ?: failure
        // Identify OkHttp's transport errors without depending on its internal API or messages.
        return when (cause?.javaClass?.name) {
            "okhttp3.internal.http2.StreamResetException" -> error("HTTP2_STREAM_RESET", "The HTTP/2 image stream was reset before the final image arrived.")
            "okhttp3.internal.http2.ConnectionShutdownException" -> error("HTTP2_CONNECTION_CLOSED", "The HTTP/2 connection closed before the final image arrived.")
            else -> when (cause) {
                is ImageResponseLimitException -> error("RESPONSE_SIZE", "The image response exceeded the device's safe size limit.")
                is UnknownHostException -> error("DNS", "The phone could not resolve api.openai.com. Check its internet connection or DNS settings.")
                is SSLException -> error("TLS", "A secure connection to OpenAI failed. Check the phone's date and any VPN or HTTPS filtering.")
                is SocketTimeoutException -> error("SOCKET_TIMEOUT", "The connection timed out while ${phase.diagnostic}.")
                is InterruptedIOException -> error("TRANSFER_TIMEOUT", "The request exceeded a connection or transfer time limit while ${phase.diagnostic}.")
                is ConnectException -> error("CONNECT", "The phone could not connect to OpenAI. Check its internet connection.")
                is EOFException -> error("RESPONSE_EOF", "The connection ended before a complete response arrived.")
                is SocketException -> error("SOCKET_CLOSED", "The network connection closed while ${phase.diagnostic}.")
                is ProtocolException -> error("HTTP_PROTOCOL", "The connection returned an incomplete or invalid HTTP response.")
                is IOException -> error("CONNECTION_LOST", "The connection was interrupted while ${phase.diagnostic}.")
                else -> error("RESPONSE_FORMAT", "The connection returned an unexpected response format.")
            }
        }
    }

    fun error(code: String, description: String): ImageApiException {
        val seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
        val submitted = if (phase != ImageRequestPhase.Connecting) " A submitted request may still finish and be billed." else ""
        val response = status?.let { "HTTP $it" } ?: "no response headers"
        val serverId = serverRequestId?.let { "\nOpenAI request ID: $it." }.orEmpty()
        return ImageApiException(
            "$description\n\nStage: ${phase.diagnostic}. Elapsed: ${seconds}s. Diagnostic: $code." +
                "\nConnection: ${protocol ?: "not established"}. Response: $response. Read: ${bytes.get()} bytes." +
                "\nClient request ID: $clientRequestId.$serverId" +
                "\nNo automatic retry was made.$submitted",
        )
    }
}
