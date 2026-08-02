package io.cruxstack.metabase.prometheus

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream

class HttpContractTest {
    @Test
    fun `uses exact instant range label-values and build-info wire contracts`() = withServer { server ->
        val vectorBody =
            """{"status":"success","warnings":["notice ✓"],"data":{"resultType":"vector","result":[{"metric":{},"value":[$WINDOW_END_SECONDS,"1"]}]}}"""
        server.context("/prometheus/") { exchange ->
            server.requests += exchange.capture()
            when (exchange.requestURI.path) {
                "/prometheus/api/v1/query" -> exchange.respondJson(200, vectorBody, gzip = true)
                "/prometheus/api/v1/query_range" -> exchange.respondJson(
                    200,
                    """{"status":"success","data":{"resultType":"matrix","result":[{"metric":{"service":"gateway","status":"500"},"values":[[$WINDOW_START_SECONDS,"1"],[$WINDOW_END_SECONDS,"2"]]}]}}""",
                )
                "/prometheus/api/v1/label/service/values" -> exchange.respondJson(
                    200,
                    """{"status":"success","data":["gateway","worker"]}""",
                )
                "/prometheus/api/v1/status/buildinfo" -> exchange.respondJson(
                    200,
                    """{"status":"success","data":{"application":"Prometheus","version":"3.0.0"}}""",
                )
                else -> exchange.respondJson(404, "not found")
            }
        }
        val config = config(server, path = "/prometheus")
        val edgeQuery =
            "sum(api_requests_per_second{route=\"/a b+c&d=?✓\",status_class=\"2xx\"})"

        PrometheusClient(config, "contract").use { client ->
            val instantResult = client.execute(
                CompiledQuery(Directive.Mode.INSTANT, edgeQuery, WINDOW, null, null),
            )
            assertEquals(vectorBody.toByteArray(StandardCharsets.UTF_8).size, instantResult.responseBytes)
            client.execute(
                CompiledQuery(
                    Directive.Mode.RANGE,
                    "api_errors_per_second{service=\"gateway\",status=\"500\"}",
                    WINDOW,
                    Duration.ofSeconds(60),
                    null,
                ),
            )
            client.execute(
                CompiledQuery(
                    Directive.Mode.LABEL_VALUES,
                    "api_latency_milliseconds_bucket{route=~\"/a b\\+c\",le=\"+Inf\"}",
                    WINDOW,
                    null,
                    "service",
                ),
            )
            assertEquals(BackendVersion("Prometheus", "3.0.0"), client.buildInfo())
        }

        assertEquals(4, server.requests.size)
        val instant = server.requests[0]
        assertEquals("POST", instant.method)
        assertEquals("/prometheus/api/v1/query", instant.path)
        assertNull(instant.rawQuery)
        assertEquals(
            "query=sum%28api_requests_per_second%7Broute%3D%22%2Fa+b%2Bc%26d%3D%3F" +
                "%E2%9C%93%22%2Cstatus_class%3D%222xx%22%7D%29" +
                "&time=$WINDOW_END_SECONDS&timeout=2m",
            instant.body,
        )
        assertEquals(listOf(FORM_CONTENT_TYPE), instant.header("Content-Type"))

        val range = server.requests[1]
        assertEquals("POST", range.method)
        assertEquals("/prometheus/api/v1/query_range", range.path)
        assertNull(range.rawQuery)
        assertEquals(
            "query=api_errors_per_second%7Bservice%3D%22gateway%22%2Cstatus%3D%22500%22%7D" +
                "&start=$WINDOW_START_SECONDS&end=$WINDOW_END_SECONDS&step=1m&timeout=2m",
            range.body,
        )
        assertEquals(listOf(FORM_CONTENT_TYPE), range.header("Content-Type"))

        val labelValues = server.requests[2]
        assertEquals("GET", labelValues.method)
        assertEquals("/prometheus/api/v1/label/service/values", labelValues.path)
        assertEquals(
            "match%5B%5D=api_latency_milliseconds_bucket%7Broute%3D%7E%22%2Fa+b%5C%2Bc%22" +
                "%2Cle%3D%22%2BInf%22%7D&start=$WINDOW_START_SECONDS&end=$WINDOW_END_SECONDS",
            labelValues.rawQuery,
        )
        assertEquals("", labelValues.body)
        assertTrue(labelValues.header("Content-Type").isEmpty())

        val buildInfo = server.requests[3]
        assertEquals("GET", buildInfo.method)
        assertEquals("/prometheus/api/v1/status/buildinfo", buildInfo.path)
        assertNull(buildInfo.rawQuery)
        assertEquals("", buildInfo.body)
        assertTrue(buildInfo.header("Content-Type").isEmpty())

        server.requests.forEach { request ->
            assertTrue(request.path.startsWith("/prometheus/"))
            assertTrue(request.header("X-Scope-OrgID").isEmpty())
            assertTrue(request.header("Authorization").isEmpty())
        }
    }

    @Test
    fun `sends optional tenant and authentication headers exactly once`() = withServer { server ->
        server.context("/api/v1/status/buildinfo") { exchange ->
            server.requests += exchange.capture()
            exchange.respondJson(404, "not found")
        }

        PrometheusClient(config(server)).use { client -> assertNull(client.buildInfo()) }
        PrometheusClient(
            config(
                server,
                overrides = mapOf(
                    "tenant-id" to "test",
                    "auth-mode" to "basic",
                    "username" to "reader",
                    "password" to "basic-password",
                ),
            ),
        ).use { client -> assertNull(client.buildInfo()) }
        PrometheusClient(
            config(
                server,
                overrides = mapOf(
                    "tenant-id" to "test",
                    "auth-mode" to "bearer",
                    "bearer-token" to "bearer-token-value",
                ),
            ),
        ).use { client -> assertNull(client.buildInfo()) }

        assertEquals(3, server.requests.size)
        assertTrue(server.requests[0].header("X-Scope-OrgID").isEmpty())
        assertTrue(server.requests[0].header("Authorization").isEmpty())

        val expectedBasic = Base64.getEncoder().encodeToString(
            "reader:basic-password".toByteArray(StandardCharsets.UTF_8),
        )
        assertEquals(listOf("test"), server.requests[1].header("X-Scope-OrgID"))
        assertEquals(listOf("Basic $expectedBasic"), server.requests[1].header("Authorization"))
        assertEquals(listOf("test"), server.requests[2].header("X-Scope-OrgID"))
        assertEquals(listOf("Bearer bearer-token-value"), server.requests[2].header("Authorization"))
    }

    @Test
    fun `connection test sends exactly one bounded instant POST`() = withServer { server ->
        server.context("/prometheus/api/v1/query") { exchange ->
            server.requests += exchange.capture()
            exchange.respondJson(
                200,
                """{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":[$CONNECTION_TEST_SECONDS,"1"]}]}}""",
            )
        }
        val details = mapOf(
            "url" to server.uri("/prometheus").toString(),
            "connect-timeout" to "30s",
            "query-timeout" to "10m",
            "maximum-data-points" to 10_000,
            "maximum-returned-rows" to 10_000,
            "maximum-response-size" to 8 * 1024 * 1024,
        )

        assertTrue(PrometheusDriver.canConnect(details))

        assertEquals(1, server.requests.size)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/prometheus/api/v1/query", request.path)
        assertEquals("query=vector%281%29&time=$CONNECTION_TEST_SECONDS&timeout=5s", request.body)
        assertEquals(listOf(FORM_CONTENT_TYPE), request.header("Content-Type"))
    }

    @Test
    fun `rejects redirects to write admin debug alert and rule handlers`() = withServer { server ->
        val forbiddenPaths = listOf(
            "/prometheus/api/v1/write",
            "/prometheus/api/v1/admin",
            "/prometheus/debug/pprof",
            "/prometheus/api/v1/alerts",
            "/prometheus/api/v1/rules",
        )
        val redirectIndex = AtomicInteger()
        val forbiddenHits = AtomicInteger()
        server.context("/prometheus/api/v1/query") { exchange ->
            server.requests += exchange.capture()
            exchange.redirect(307, forbiddenPaths[redirectIndex.getAndIncrement()])
        }
        forbiddenPaths.forEach { path ->
            server.context(path) { exchange ->
                forbiddenHits.incrementAndGet()
                exchange.respondJson(500, "forbidden handler was reached")
            }
        }
        val client = PrometheusClient(config(server, path = "/prometheus"))
        client.use {
            forbiddenPaths.forEach {
                val error = assertThrows(DriverQueryException::class.java) { client.execute(instantQuery()) }
                assertEquals(DriverQueryException.Category.HTTP, error.category)
            }
        }

        assertEquals(forbiddenPaths.size, server.requests.size)
        assertEquals(0, forbiddenHits.get())
    }

    @Test
    fun `rejects POST method-changing redirects without retrying`() = withServer { server ->
        val statuses = listOf(301, 302, 303)
        val requestIndex = AtomicInteger()
        server.context("/api/v1/query") { exchange ->
            server.requests += exchange.capture()
            exchange.redirect(statuses[requestIndex.getAndIncrement()], "/api/v1/query?redirected=true")
        }

        PrometheusClient(config(server)).use { client ->
            statuses.forEach {
                val error = assertThrows(DriverQueryException::class.java) { client.execute(instantQuery()) }
                assertEquals(DriverQueryException.Category.HTTP, error.category)
            }
        }

        assertEquals(3, server.requests.size)
        assertTrue(server.requests.none { it.rawQuery == "redirected=true" })
    }

    @Test
    fun `rejects cross-authority redirects before credentials can cross`() {
        LocalServer().use { source ->
            LocalServer().use { destination ->
                val destinationHits = AtomicInteger()
                source.context("/api/v1/query") { exchange ->
                    source.requests += exchange.capture()
                    exchange.redirect(307, destination.uri("/api/v1/query").toString())
                }
                destination.context("/api/v1/query") { exchange ->
                    destinationHits.incrementAndGet()
                    destination.requests += exchange.capture()
                    exchange.respondJson(200, vectorResponse())
                }
                val config = config(
                    source,
                    overrides = mapOf("auth-mode" to "bearer", "bearer-token" to "authority-token"),
                )

                val error = assertThrows(DriverQueryException::class.java) {
                    PrometheusClient(config).use { it.execute(instantQuery()) }
                }

                assertEquals(DriverQueryException.Category.HTTP, error.category)
                assertEquals(listOf("Bearer authority-token"), source.requests.single().header("Authorization"))
                assertEquals(0, destinationHits.get())
                assertTrue(destination.requests.isEmpty())
            }
        }
    }

    @Test
    fun `enforces decompressed response limits for chunked and gzip bodies`() = withServer { server ->
        val responseIndex = AtomicInteger()
        server.context("/api/v1/query") { exchange ->
            server.requests += exchange.capture()
            val gzip = responseIndex.getAndIncrement() == 1
            exchange.respondJson(200, "x".repeat(1_024), gzip = gzip, chunked = true)
        }
        val config = config(server, overrides = mapOf("maximum-response-size" to 128))

        PrometheusClient(config).use { client ->
            repeat(2) {
                val error = assertThrows(DriverQueryException::class.java) { client.execute(instantQuery()) }
                assertEquals(DriverQueryException.Category.GUARDRAIL, error.category)
            }
        }

        assertEquals(2, server.requests.size)
    }

    @Test
    fun `maps backend timeout canceled and unknown errors and redacts echoed secrets`() = withServer { server ->
        val errors = listOf(
            """{"status":"error","errorType":"timeout","error":"deadline exceeded"}""",
            """{"status":"error","errorType":"canceled","error":"request canceled"}""",
            """{"status":"error","errorType":"storage","error":"echoed bearer-token-value"}""",
            """{"status":"error","errorType":"storage","error":"echoed basic-password"}""",
        )
        val responseIndex = AtomicInteger()
        server.context("/api/v1/query") { exchange ->
            server.requests += exchange.capture()
            exchange.respondJson(422, errors[responseIndex.getAndIncrement()])
        }

        val timeout = assertThrows(DriverQueryException::class.java) {
            PrometheusClient(config(server)).use { it.execute(instantQuery()) }
        }
        assertEquals(DriverQueryException.Category.TIMEOUT, timeout.category)

        val canceled = assertThrows(DriverQueryException::class.java) {
            PrometheusClient(config(server)).use { it.execute(instantQuery()) }
        }
        assertEquals(DriverQueryException.Category.CANCELED, canceled.category)

        val bearer = assertThrows(DriverQueryException::class.java) {
            PrometheusClient(
                config(
                    server,
                    overrides = mapOf("auth-mode" to "bearer", "bearer-token" to "bearer-token-value"),
                ),
            ).use { it.execute(instantQuery()) }
        }
        assertEquals(DriverQueryException.Category.BACKEND, bearer.category)
        assertFalse(throwableText(bearer).contains("bearer-token-value"))
        assertTrue(bearer.message.orEmpty().contains("<redacted>"))

        val basic = assertThrows(DriverQueryException::class.java) {
            PrometheusClient(
                config(
                    server,
                    overrides = mapOf(
                        "auth-mode" to "basic",
                        "username" to "reader",
                        "password" to "basic-password",
                    ),
                ),
            ).use { it.execute(instantQuery()) }
        }
        assertEquals(DriverQueryException.Category.BACKEND, basic.category)
        assertFalse(throwableText(basic).contains("basic-password"))
        assertTrue(basic.message.orEmpty().contains("<redacted>"))
    }

    @Test
    fun `public driver operations reach only fixed read endpoints`() = withServer { server ->
        server.context("/") { exchange ->
            server.requests += exchange.capture()
            when (exchange.requestURI.path) {
                "/prometheus/api/v1/query" -> exchange.respondJson(200, vectorResponse())
                "/prometheus/api/v1/query_range" -> exchange.respondJson(
                    200,
                    """{"status":"success","data":{"resultType":"matrix","result":[]}}""",
                )
                "/prometheus/api/v1/label/service/values" -> exchange.respondJson(
                    200,
                    """{"status":"success","data":["gateway"]}""",
                )
                "/prometheus/api/v1/status/buildinfo" -> exchange.respondJson(
                    200,
                    """{"status":"success","data":{"application":"Prometheus","version":"3.0.0"}}""",
                )
                else -> exchange.respondJson(404, "not found")
            }
        }
        val details = mapOf("url" to server.uri("/prometheus").toString())
        val fixedRange = "@$WINDOW_START/$WINDOW_END"

        PrometheusDriver.validateConfig(details)
        assertTrue(PrometheusDriver.canConnect(details))
        assertEquals(BackendVersion("Prometheus", "3.0.0"), PrometheusDriver.dbmsVersion(details))
        PrometheusDriver.startQuery(
            details,
            "# metabase-mimir mode=instant time=$fixedRange\n" +
                "api_requests_per_second{service=\"gateway\"}",
        ).await()
        PrometheusDriver.startQuery(
            details,
            "# metabase-mimir mode=range step=60s time=$fixedRange\n" +
                "api_errors_per_second{service=\"gateway\"}",
        ).await()
        PrometheusDriver.startQuery(
            details,
            "# metabase-mimir mode=label-values time=$fixedRange label=service\n" +
                "api_latency_milliseconds_sum{route=\"/v1/items\"}",
        ).await()

        val paths = server.requests.map(CapturedRequest::path)
        val allowedPaths = setOf(
            "/prometheus/api/v1/query",
            "/prometheus/api/v1/query_range",
            "/prometheus/api/v1/label/service/values",
            "/prometheus/api/v1/status/buildinfo",
        )
        assertEquals(allowedPaths, paths.toSet())
        assertEquals(2, paths.count { it == "/prometheus/api/v1/query" })
        assertEquals(5, paths.size)
        assertTrue(paths.all { it in allowedPaths })
        assertTrue(paths.none { path ->
            listOf("/write", "/admin", "/debug", "/alerts", "/rules").any(path::contains)
        })
    }

    private fun config(
        server: LocalServer,
        path: String = "",
        overrides: Map<String, Any> = emptyMap(),
    ): DriverConfig = DriverConfig.from(mapOf("url" to server.uri(path).toString()) + overrides)

    private fun instantQuery(): CompiledQuery = CompiledQuery(
        Directive.Mode.INSTANT,
        "api_requests_per_second{service=\"gateway\",route=\"/v1/items\"}",
        WINDOW,
        null,
        null,
    )

    private fun vectorResponse(): String =
        """{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":[$WINDOW_END_SECONDS,"1"]}]}}"""

    private fun throwableText(exception: Throwable): String = generateSequence(exception) { it.cause }
        .joinToString("\n") { it.toString() }

    private fun withServer(test: (LocalServer) -> Unit) {
        LocalServer().use(test)
    }

    private class LocalServer : AutoCloseable {
        private val executor: ExecutorService = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@LocalServer.executor
            start()
        }
        val requests: MutableList<CapturedRequest> = Collections.synchronizedList(mutableListOf())

        fun context(path: String, handler: (HttpExchange) -> Unit) {
            server.createContext(path) { exchange -> handler(exchange) }
        }

        fun uri(path: String): URI = URI("http://127.0.0.1:${server.address.port}$path")

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val rawQuery: String?,
        val headers: Map<String, List<String>>,
        val body: String,
    ) {
        fun header(name: String): List<String> = headers.entries
            .firstOrNull { (key) -> key.equals(name, ignoreCase = true) }
            ?.value
            .orEmpty()
    }

    private fun HttpExchange.capture(): CapturedRequest = CapturedRequest(
        method = requestMethod,
        path = requestURI.path,
        rawQuery = requestURI.rawQuery,
        headers = requestHeaders.entries.associate { it.key to it.value.toList() },
        body = requestBody.use { it.readAllBytes().toString(StandardCharsets.UTF_8) },
    )

    private fun HttpExchange.redirect(status: Int, location: String) {
        responseHeaders.add("Location", location)
        sendResponseHeaders(status, -1)
        close()
    }

    private fun HttpExchange.respondJson(
        status: Int,
        body: String,
        gzip: Boolean = false,
        chunked: Boolean = false,
    ) {
        val raw = body.toByteArray(StandardCharsets.UTF_8)
        val bytes = if (gzip) {
            ByteArrayOutputStream().use { output ->
                GZIPOutputStream(output).use { it.write(raw) }
                output.toByteArray()
            }
        } else {
            raw
        }
        responseHeaders.add("Content-Type", "application/json")
        if (gzip) responseHeaders.add("Content-Encoding", "gzip")
        sendResponseHeaders(status, if (chunked) 0 else bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded; charset=UTF-8"
        val WINDOW_START: Instant = Instant.parse("2026-01-01T00:00:00Z")
        val WINDOW_END: Instant = Instant.parse("2026-01-01T06:00:00Z")
        val WINDOW = QueryTimeRange(WINDOW_START, WINDOW_END)
        val WINDOW_START_SECONDS: Long = WINDOW_START.epochSecond
        val WINDOW_END_SECONDS: Long = WINDOW_END.epochSecond
        val CONNECTION_TEST_SECONDS: Long = Instant.EPOCH.epochSecond
    }
}
