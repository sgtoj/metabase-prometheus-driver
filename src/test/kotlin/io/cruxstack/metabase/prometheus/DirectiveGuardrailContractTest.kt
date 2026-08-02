package io.cruxstack.metabase.prometheus

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class DirectiveGuardrailContractTest {
    @Test
    fun `rejects keys that are incompatible with the selected mode`() {
        listOf(
            "# metabase-mimir mode=instant step=60s\napi_requests_per_second",
            "# metabase-mimir mode=range label=service\napi_requests_per_second",
            "# metabase-mimir mode=label-values label=service step=60s\napi_requests_per_second",
        ).forEach { query ->
            assertThrows(IllegalArgumentException::class.java) { Directive.parse(query) }
        }
    }

    @Test
    fun `rejects misplaced multiple and malformed directives`() {
        listOf(
            "api_requests_per_second\n# metabase-mimir mode=range",
            "# metabase-mimir mode=range\n# metabase-mimir mode=instant\napi_requests_per_second",
            "# metabase-mimir mode\napi_requests_per_second",
            "# metabase-mimir mode=\napi_requests_per_second",
            "# metabase-mimir =range\napi_requests_per_second",
            "# metabase-mimir mode=range mode=instant\napi_requests_per_second",
            "# metabase-mimir mode=range unsupported=value\napi_requests_per_second",
        ).forEach { query ->
            assertThrows(IllegalArgumentException::class.java) { Directive.parse(query) }
        }
    }

    @Test
    fun `bounds automatic steps by the configured minimum`() {
        val config = config(
            mapOf(
                "maximum-query-range" to "6h",
                "maximum-data-points" to 1_000,
                "minimum-range-step" to "1m",
            ),
        )
        val query = QueryCompiler.compile(
            fixedRangeQuery("step=auto", "sum_over_time(api_requests_per_second[\$__interval])"),
            emptyMap(),
            config,
            FIXED_CLOCK,
        )

        assertEquals(Duration.ofMinutes(1), query.step)
        assertEquals("sum_over_time(api_requests_per_second[1m])", query.promQl)
    }

    @Test
    fun `counts both range endpoints in the data point limit`() {
        val accepted = QueryCompiler.compile(
            fixedRangeQuery("step=60s", "api_requests_per_second"),
            emptyMap(),
            config(
                mapOf(
                    "maximum-query-range" to "6h",
                    "maximum-data-points" to 361,
                    "minimum-range-step" to "1s",
                ),
            ),
            FIXED_CLOCK,
        )
        assertEquals(Duration.ofSeconds(60), accepted.step)

        assertThrows(IllegalArgumentException::class.java) {
            QueryCompiler.compile(
                fixedRangeQuery("step=60s", "api_requests_per_second"),
                emptyMap(),
                config(
                    mapOf(
                        "maximum-query-range" to "6h",
                        "maximum-data-points" to 360,
                        "minimum-range-step" to "1s",
                    ),
                ),
                FIXED_CLOCK,
            )
        }
    }

    @Test
    fun `accepts the exact maximum range and rejects any larger range`() {
        val config = config(
            mapOf(
                "maximum-query-range" to "6h",
                "maximum-data-points" to 361,
                "minimum-range-step" to "1s",
            ),
        )
        val accepted = QueryCompiler.compile(
            fixedRangeQuery("step=60s", "api_requests_per_second"),
            emptyMap(),
            config,
            FIXED_CLOCK,
        )
        assertEquals(QueryTimeRange(START, END), accepted.timeRange)

        val tooLong = "# metabase-mimir mode=range step=60s " +
            "time=@$START/${END.plusNanos(1)}\napi_requests_per_second"
        assertThrows(IllegalArgumentException::class.java) {
            QueryCompiler.compile(tooLong, emptyMap(), config, FIXED_CLOCK)
        }
    }

    @Test
    fun `rejects an invalid query before making an HTTP request`() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/prometheus") { exchange ->
                requests.incrementAndGet()
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
            }
            start()
        }
        try {
            val error = assertThrows(DriverQueryException::class.java) {
                PrometheusDriver.startQuery(
                    mapOf("url" to "http://127.0.0.1:${server.address.port}/prometheus"),
                    "# metabase-mimir mode=instant step=60s\napi_requests_per_second",
                )
            }
            assertEquals(DriverQueryException.Category.VALIDATION, error.category)
            assertEquals(0, requests.get())
        } finally {
            server.stop(0)
        }
    }

    private fun config(overrides: Map<String, Any>): DriverConfig =
        DriverConfig.from(mapOf("url" to "http://127.0.0.1/prometheus") + overrides)

    private fun fixedRangeQuery(step: String, promQl: String): String =
        "# metabase-mimir mode=range $step time=@$START/$END\n$promQl"

    companion object {
        private val START = Instant.parse("2026-01-01T00:00:00Z")
        private val END = Instant.parse("2026-01-01T06:00:00Z")
        private val FIXED_CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
    }
}
