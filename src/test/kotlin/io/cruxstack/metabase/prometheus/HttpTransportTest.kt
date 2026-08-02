package io.cruxstack.metabase.prometheus

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream

class HttpTransportTest {
    @Test
    fun `encodes Prometheus 3 label names with values escaping`() {
        assertEquals("job", PrometheusClient.encodeLabelName("job"))
        assertEquals("U__http_2e_status_2f_code", PrometheusClient.encodeLabelName("http.status/code"))
        assertEquals("U__service__name_3bb_", PrometheusClient.encodeLabelName("service_nameλ"))
    }

    @Test
    fun `sends an exact compressed tenant-scoped instant request`() = withServer { server ->
        val captured = AtomicReference<CapturedRequest>()
        server.context("/prometheus/api/v1/query") { exchange ->
            captured.set(exchange.capture())
            exchange.respondJson(
                200,
                """{"status":"success","data":{"resultType":"vector","result":[{"metric":{"job":"api"},"value":[1,"2"]}]}}""",
                gzip = true,
            )
        }
        val config = config(
            server,
            mapOf("url" to server.uri("/prometheus").toString(), "tenant-id" to "demo"),
        )
        val query = CompiledQuery(
            Directive.Mode.INSTANT,
            "vector(1)",
            QueryTimeRange(Instant.EPOCH, Instant.ofEpochSecond(5)),
            null,
            null,
        )
        val result = PrometheusClient(config, "test").execute(query)
        val request = captured.get()

        assertEquals("POST", request.method)
        assertEquals("/prometheus/api/v1/query", request.path)
        assertEquals("query=vector%281%29&time=5&timeout=2m", request.body)
        assertEquals("demo", request.headers.getValue("X-scope-orgid").single())
        assertEquals("gzip", request.headers.getValue("Accept-encoding").single())
        assertEquals("${PrometheusClient.USER_AGENT} test", request.headers.getValue("User-agent").single())
        assertEquals(2.0, result.rows.single().last())
    }

    @Test
    fun `sends basic and bearer authorization without duplicating headers`() = withServer { server ->
        val headers = mutableListOf<Map<String, List<String>>>()
        server.context("/api/v1/status/buildinfo") { exchange ->
            headers += exchange.requestHeaders.entries.associate { it.key to it.value.toList() }
            exchange.respondJson(404, "not found")
        }

        val basic = config(
            server,
            mapOf("auth-mode" to "basic", "username" to "alice", "password" to "s3cret"),
        )
        PrometheusClient(basic).buildInfo()
        val expectedBasic = Base64.getEncoder().encodeToString("alice:s3cret".toByteArray(StandardCharsets.UTF_8))
        assertEquals(listOf("Basic $expectedBasic"), headers[0].getValue("Authorization"))

        val bearer = config(server, mapOf("auth-mode" to "bearer", "bearer-token" to "token-value"))
        PrometheusClient(bearer).buildInfo()
        assertEquals(listOf("Bearer token-value"), headers[1].getValue("Authorization"))
    }

    @Test
    fun `follows same-authority redirects and rejects cross-authority redirects`() = withServer { server ->
        server.context("/same") { exchange ->
            if (exchange.requestURI.rawQuery == null) {
                exchange.responseHeaders.add("Location", "/same?redirected=true")
                exchange.sendResponseHeaders(307, -1)
                exchange.close()
            } else {
                exchange.respondJson(200, "ok")
            }
        }
        server.context("/cross") { exchange ->
            exchange.responseHeaders.add("Location", "http://example.invalid/stolen")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        val config = config(server)
        val transport = HttpTransport(config, "test")
        assertEquals("ok", transport.execute(HttpCall(HttpCall.Method.GET, server.uri("/same"))).body)
        val error = assertThrows(DriverQueryException::class.java) {
            transport.execute(HttpCall(HttpCall.Method.GET, server.uri("/cross")))
        }
        assertEquals(DriverQueryException.Category.HTTP, error.category)
    }

    @Test
    fun `rejects oversized responses before result parsing`() = withServer { server ->
        server.context("/large") { exchange -> exchange.respondJson(200, "123456789") }
        val config = config(server, mapOf("maximum-response-size" to 8))
        val error = assertThrows(DriverQueryException::class.java) {
            HttpTransport(config, "test").execute(HttpCall(HttpCall.Method.GET, server.uri("/large")))
        }
        assertEquals(DriverQueryException.Category.GUARDRAIL, error.category)
    }

    @Test
    fun `cancels a slow local request at the configured timeout`() = withServer { server ->
        server.context("/slow") { exchange ->
            Thread.sleep(500)
            runCatching { exchange.respondJson(200, "ok") }
        }
        val config = config(server, mapOf("query-timeout" to "100ms"))
        val started = System.nanoTime()
        val error = assertThrows(DriverQueryException::class.java) {
            HttpTransport(config, "test").execute(HttpCall(HttpCall.Method.GET, server.uri("/slow")))
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertEquals(DriverQueryException.Category.TIMEOUT, error.category)
        assertTrue(elapsedMillis < 1_000, "timeout took ${elapsedMillis}ms")
    }

    @Test
    fun `times out while a response body is stalled`() = withServer { server ->
        server.context("/stalled-body") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write("{".toByteArray(StandardCharsets.UTF_8))
            exchange.responseBody.flush()
            Thread.sleep(5_000)
            exchange.close()
        }
        val config = config(server, mapOf("query-timeout" to "100ms"))
        val started = System.nanoTime()
        val error = assertThrows(DriverQueryException::class.java) {
            HttpTransport(config, "test").use {
                it.execute(HttpCall(HttpCall.Method.GET, server.uri("/stalled-body")))
            }
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertEquals(DriverQueryException.Category.TIMEOUT, error.category)
        assertTrue(elapsedMillis < 1_000, "body timeout took ${elapsedMillis}ms")
    }

    @Test
    fun `cancels an in-flight driver request explicitly`() = withServer { server ->
        server.context("/api/v1/query") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write("{\"status\":".toByteArray(StandardCharsets.UTF_8))
            exchange.responseBody.flush()
            Thread.sleep(5_000)
            exchange.close()
        }
        val started = System.nanoTime()
        val execution = PrometheusDriver.startQuery(
            mapOf("url" to server.uri("").toString(), "query-timeout" to "10s"),
            "vector(1)",
        )
        Thread.sleep(100)
        execution.cancel()
        val error = assertThrows(DriverQueryException::class.java) { execution.await() }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertEquals(DriverQueryException.Category.CANCELED, error.category)
        assertTrue(elapsedMillis < 1_000, "cancellation took ${elapsedMillis}ms")
    }

    @Test
    fun `surfaces safe Prometheus errors and build information`() = withServer { server ->
        server.context("/api/v1/query") { exchange ->
            exchange.respondJson(422, """{"status":"error","errorType":"bad_data","error":"parse failed"}""")
        }
        server.context("/api/v1/status/buildinfo") { exchange ->
            exchange.respondJson(
                200,
                """{"status":"success","data":{"application":"Mimir","version":"3.1.4"}}""",
            )
        }
        val client = PrometheusClient(config(server))
        val query = CompiledQuery(
            Directive.Mode.INSTANT,
            "bad",
            QueryTimeRange(Instant.EPOCH, Instant.EPOCH),
            null,
            null,
        )
        val error = assertThrows(DriverQueryException::class.java) { client.execute(query) }
        assertEquals(DriverQueryException.Category.VALIDATION, error.category)
        assertEquals("Mimir query failed (bad_data): parse failed", error.message)
        assertEquals(BackendVersion("Mimir", "3.1.4"), client.buildInfo())
    }

    @Test
    fun `bounds build information JSON structure`() = withServer { server ->
        val padding = List(1_001) { "null" }.joinToString(",")
        server.context("/api/v1/status/buildinfo") { exchange ->
            exchange.respondJson(
                200,
                """{"padding":[$padding],"status":"success","data":{"version":"1"}}""",
            )
        }

        val error = assertThrows(DriverQueryException::class.java) {
            PrometheusClient(config(server)).use { it.buildInfo() }
        }
        assertEquals(DriverQueryException.Category.GUARDRAIL, error.category)
    }

    private fun config(server: LocalServer, overrides: Map<String, Any> = emptyMap()): DriverConfig =
        DriverConfig.from(mapOf("url" to server.uri("").toString()) + overrides)

    private fun withServer(test: (LocalServer) -> Unit) {
        LocalServer().use(test)
    }

    private class LocalServer : AutoCloseable {
        private val executor: ExecutorService = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@LocalServer.executor
            start()
        }

        fun context(path: String, handler: (HttpExchange) -> Unit) {
            server.createContext(path) { exchange -> handler(exchange) }
        }

        fun uri(path: String) = java.net.URI("http://127.0.0.1:${server.address.port}$path")

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
        val body: String,
    )

    private fun HttpExchange.capture(): CapturedRequest = CapturedRequest(
        method = requestMethod,
        path = requestURI.path,
        headers = requestHeaders.entries.associate { it.key to it.value.toList() },
        body = requestBody.use { it.readAllBytes().toString(StandardCharsets.UTF_8) },
    )

    private fun HttpExchange.respondJson(status: Int, body: String, gzip: Boolean = false) {
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
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
