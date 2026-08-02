package io.cruxstack.metabase.prometheus

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

internal data class HttpCall(
    val method: Method,
    val uri: URI,
    val parameters: List<Pair<String, String>> = emptyList(),
) {
    enum class Method {
        GET,
        POST,
    }
}

internal data class HttpPayload(
    val statusCode: Int,
    val body: String,
    val byteCount: Int,
    val headers: Map<String, List<String>>,
)

internal class HttpTransport(
    private val config: DriverConfig,
    private val userAgent: String,
) : AutoCloseable {
    private val client = HttpClient.newBuilder()
        .connectTimeout(config.connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun execute(call: HttpCall, cancellation: RequestCancellation = RequestCancellation()): HttpPayload {
        cancellation.start(config.queryTimeout)
        var current = call
        val originalUri = call.uri
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val response = send(current, cancellation)
            if (!isRedirect(response.statusCode())) {
                return readPayload(response, cancellation)
            }
            val location = response.headers().firstValue("location").orElse(null)
            response.body().close()
            if (redirectCount == MAX_REDIRECTS) httpFailure("Mimir returned too many redirects")
            if (location == null) httpFailure("Mimir returned a redirect without a Location header")
            val target = current.uri.resolve(location)
            if (!sameAuthority(originalUri, target)) {
                httpFailure("Mimir redirected the request to a different authority")
            }
            if (target.rawPath != originalUri.rawPath) {
                httpFailure("Mimir redirected the request away from the original API endpoint")
            }
            if (current.method == HttpCall.Method.POST && response.statusCode() in setOf(301, 302, 303)) {
                httpFailure("Mimir returned a redirect that would change the POST request method")
            }
            current = when (response.statusCode()) {
                301, 302, 303 -> current.copy(uri = target, parameters = emptyList())
                else -> current.copy(uri = target)
            }
        }
        error("redirect loop terminated unexpectedly")
    }

    private fun send(
        call: HttpCall,
        cancellation: RequestCancellation,
    ): HttpResponse<InputStream> {
        val encodedParameters = encodeForm(call.parameters)
        val uri = if (call.method == HttpCall.Method.GET && encodedParameters.isNotEmpty()) {
            val separator = if (call.uri.rawQuery.isNullOrEmpty()) "?" else "&"
            URI.create("${call.uri}$separator$encodedParameters")
        } else {
            call.uri
        }
        val remainingMillis = cancellation.remainingMillis()
        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMillis(remainingMillis))
            .header("Accept", "application/json")
            .header("Accept-Encoding", "gzip")
            .header("User-Agent", userAgent)
        if (config.tenantId != null) builder.header("X-Scope-OrgID", config.tenantId)
        when (val authentication = config.authentication) {
            DriverConfig.Authentication.None -> Unit
            is DriverConfig.Authentication.Basic -> {
                val credentials = "${authentication.username}:${authentication.password}"
                val value = Base64.getEncoder().encodeToString(credentials.toByteArray(StandardCharsets.UTF_8))
                builder.header("Authorization", "Basic $value")
            }
            is DriverConfig.Authentication.Bearer -> builder.header("Authorization", "Bearer ${authentication.token}")
        }
        when (call.method) {
            HttpCall.Method.GET -> builder.GET()
            HttpCall.Method.POST -> builder
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(encodedParameters, StandardCharsets.UTF_8))
        }

        val future = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        cancellation.register(future)
        return try {
            future.get(remainingMillis, TimeUnit.MILLISECONDS)
        } catch (exception: CancellationException) {
            throw DriverQueryException(DriverQueryException.Category.CANCELED, "Mimir query was canceled", exception)
        } catch (exception: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw DriverQueryException(DriverQueryException.Category.CANCELED, "Mimir query was canceled", exception)
        } catch (exception: TimeoutException) {
            future.cancel(true)
            throw DriverQueryException(DriverQueryException.Category.TIMEOUT, "Mimir query timed out", exception)
        } catch (exception: ExecutionException) {
            when (val cause = exception.cause) {
                is HttpTimeoutException -> throw DriverQueryException(
                    DriverQueryException.Category.TIMEOUT,
                    "Mimir query timed out",
                    cause,
                )
                is IOException -> throw DriverQueryException(
                    DriverQueryException.Category.CONNECTION,
                    "Could not connect to Mimir",
                    cause,
                )
                else -> throw DriverQueryException(
                    DriverQueryException.Category.CONNECTION,
                    "Mimir request failed",
                    cause ?: exception,
                )
            }
        } finally {
            cancellation.clear(future)
        }
    }

    private fun readPayload(
        response: HttpResponse<InputStream>,
        cancellation: RequestCancellation,
    ): HttpPayload {
        val declaredSize = response.headers().firstValueAsLong("content-length").orElse(-1)
        val contentEncoding = response.headers().firstValue("content-encoding").orElse("")
        val isGzip = contentEncoding.split(',').any { it.trim().equals("gzip", ignoreCase = true) }
        if (!isGzip && declaredSize > config.maximumResponseBytes) {
            response.body().close()
            excessiveResponse()
        }
        val rawBody = response.body()
        cancellation.register(rawBody)
        val timedOut = AtomicBoolean(false)
        val timeoutMillis = try {
            cancellation.remainingMillis()
        } catch (exception: DriverQueryException) {
            cancellation.clear(rawBody)
            rawBody.close()
            throw exception
        }
        val timeoutGuard = Thread.ofVirtual().name("metabase-prometheus-response-timeout").start {
            var deadlineReached = true
            try {
                Thread.sleep(Duration.ofMillis(timeoutMillis))
            } catch (_: InterruptedException) {
                deadlineReached = false
            }
            if (deadlineReached) {
                timedOut.set(true)
                runCatching { rawBody.close() }
            }
        }
        try {
            val source = if (isGzip) {
                GZIPInputStream(rawBody)
            } else {
                rawBody
            }
            val bytes = source.use { readBounded(it, config.maximumResponseBytes, cancellation) }
            if (timedOut.get()) timeoutFailure()
            cancellation.checkpoint()
            return HttpPayload(
                statusCode = response.statusCode(),
                body = bytes.toString(StandardCharsets.UTF_8),
                byteCount = bytes.size,
                headers = response.headers().map(),
            )
        } catch (exception: IOException) {
            when {
                cancellation.isCanceled() -> throw DriverQueryException(
                    DriverQueryException.Category.CANCELED,
                    "Mimir query was canceled",
                    exception,
                )
                timedOut.get() -> timeoutFailure(exception)
                else -> throw DriverQueryException(
                    DriverQueryException.Category.CONNECTION,
                    "Could not read the Mimir response",
                    exception,
                )
            }
        } finally {
            timeoutGuard.interrupt()
            cancellation.clear(rawBody)
            rawBody.close()
        }
    }

    private fun readBounded(input: InputStream, maximumBytes: Int, cancellation: RequestCancellation): ByteArray {
        val output = ByteArrayOutputStream(minOf(maximumBytes, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            cancellation.checkpoint()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maximumBytes) excessiveResponse()
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun excessiveResponse(): Nothing = throw DriverQueryException(
        DriverQueryException.Category.GUARDRAIL,
        "Mimir response exceeds the configured ${config.maximumResponseBytes} byte limit",
    )

    private fun timeoutFailure(cause: Throwable? = null): Nothing = throw DriverQueryException(
        DriverQueryException.Category.TIMEOUT,
        "Mimir query timed out",
        cause,
    )

    private fun httpFailure(message: String): Nothing = throw DriverQueryException(
        DriverQueryException.Category.HTTP,
        message,
    )

    private fun sameAuthority(source: URI, target: URI): Boolean =
        source.scheme.equals(target.scheme, ignoreCase = true) &&
            source.host.equals(target.host, ignoreCase = true) &&
            effectivePort(source) == effectivePort(target) &&
            target.userInfo == null &&
            target.fragment == null

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

    companion object {
        private const val MAX_REDIRECTS = 3

        private fun isRedirect(statusCode: Int): Boolean = statusCode in setOf(301, 302, 303, 307, 308)

        fun encodeForm(parameters: List<Pair<String, String>>): String = parameters.joinToString("&") { (name, value) ->
            "${encode(name)}=${encode(value)}"
        }

        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
    }

    override fun close() {
        client.close()
    }
}

class RequestCancellation {
    private val canceled = AtomicBoolean(false)
    private val deadlineNanos = AtomicLong(Long.MAX_VALUE)
    private val request = AtomicReference<CompletableFuture<*>?>()
    private val responseBody = AtomicReference<InputStream?>()

    fun cancel() {
        canceled.set(true)
        request.get()?.cancel(true)
        runCatching { responseBody.get()?.close() }
    }

    internal fun start(timeout: Duration) {
        val timeoutNanos = try {
            timeout.toNanos()
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
        val now = System.nanoTime()
        val deadline = try {
            Math.addExact(now, timeoutNanos)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
        deadlineNanos.compareAndSet(Long.MAX_VALUE, deadline)
    }

    internal fun checkpoint() {
        if (canceled.get() || Thread.currentThread().isInterrupted) {
            throw DriverQueryException(DriverQueryException.Category.CANCELED, "Mimir query was canceled")
        }
        if (remainingNanos() <= 0) {
            throw DriverQueryException(DriverQueryException.Category.TIMEOUT, "Mimir query timed out")
        }
    }

    internal fun remainingMillis(): Long {
        checkpoint()
        val remaining = remainingNanos()
        return ((remaining - 1) / NANOS_PER_MILLISECOND + 1).coerceAtLeast(1)
    }

    private fun remainingNanos(): Long {
        val deadline = deadlineNanos.get()
        return if (deadline == Long.MAX_VALUE) Long.MAX_VALUE else deadline - System.nanoTime()
    }

    internal fun register(future: CompletableFuture<*>) {
        if (canceled.get()) {
            future.cancel(true)
            return
        }
        request.set(future)
        if (canceled.get() && request.compareAndSet(future, null)) future.cancel(true)
    }

    internal fun clear(future: CompletableFuture<*>) {
        request.compareAndSet(future, null)
    }

    internal fun register(stream: InputStream) {
        if (canceled.get()) {
            stream.close()
            return
        }
        responseBody.set(stream)
        if (canceled.get() && responseBody.compareAndSet(stream, null)) stream.close()
    }

    internal fun clear(stream: InputStream) {
        responseBody.compareAndSet(stream, null)
    }

    internal fun isCanceled(): Boolean = canceled.get()

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
