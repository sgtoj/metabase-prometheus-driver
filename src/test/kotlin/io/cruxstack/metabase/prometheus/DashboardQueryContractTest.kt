package io.cruxstack.metabase.prometheus

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Named
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class DashboardQueryContractTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("dashboardQueries")
    fun `executes each public dashboard query contract`(
        @Suppress("UNUSED_PARAMETER") name: String,
        contract: QueryContract,
    ) = withServer(contract.responseBody()) { server, captured ->
        val config = DriverConfig.from(
            mapOf(
                "url" to server.uri("/prometheus").toString(),
                "query-timeout" to "30s",
                "maximum-query-range" to "6h",
                "maximum-data-points" to 1_000,
                "minimum-range-step" to "60s",
            ),
        )
        val substituted = MetabaseParameters.substitute(
            contract.nativeQuery,
            TEMPLATE_TAGS,
            PARAMETERS_WITH_SERVICES,
            "UTC",
        )
        val parsed = Directive.parse(substituted)

        assertTrue(parsed.hasDirective)
        assertEquals(contract.renderedPromQl, parsed.promQl)
        assertFalse(parsed.promQl.contains("# metabase-mimir"))
        assertEquals(START, parsed.directive.fixedTimeRange?.start)
        assertEquals(END, parsed.directive.fixedTimeRange?.end)

        val compiled = QueryCompiler.compile(
            substituted,
            emptyMap(),
            config,
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        )
        assertEquals(contract.mode, compiled.mode)
        assertEquals(contract.finalPromQl, compiled.promQl)
        assertEquals(QueryTimeRange(START, END), compiled.timeRange)
        assertTrue(RESERVED_TOKENS.none { it in compiled.promQl })
        if (contract.mode == Directive.Mode.RANGE) {
            assertEquals(Duration.ofSeconds(60), compiled.step)
        } else {
            assertNull(compiled.step)
        }

        val result = PrometheusClient(config, "dashboard-contract").use { it.execute(compiled) }
        val request = requireNotNull(captured.get())
        assertEquals(contract.method, request.method)
        assertEquals(contract.endpoint, request.path)
        assertEquals(contract.expectedForm, request.formParameters())
        if (contract.method == "GET") {
            assertEquals("", request.body)
        } else {
            assertNull(request.rawQuery)
        }
        contract.assertConverted(result)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("optionalFilterQueries")
    fun `removes an omitted service filter without malformed matchers`(
        @Suppress("UNUSED_PARAMETER") name: String,
        promQl: String,
        expected: String,
    ) {
        val nativeQuery = "# metabase-mimir mode=instant time={{time}}\n$promQl"
        val substituted = MetabaseParameters.substitute(
            nativeQuery,
            TEMPLATE_TAGS,
            PARAMETERS_WITHOUT_SERVICES,
            "UTC",
        )
        val rendered = Directive.parse(substituted).promQl

        assertEquals(expected, rendered)
        assertFalse(rendered.contains("[["))
        assertFalse(rendered.contains("{{service}}"))
        assertFalse(rendered.contains("{,"))
        assertFalse(rendered.contains(",}"))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fallbackScenarios")
    fun `distinguishes zero from no data for the error fallback`(
        @Suppress("UNUSED_PARAMETER") name: String,
        scenario: FallbackScenario,
    ) = withServer(scenario.responseBody()) { server, captured ->
        val config = contractConfig(server)
        val rendered = MetabaseParameters.substitute(
            "# metabase-mimir mode=instant time={{time}}\n$ERROR_FALLBACK_TEMPLATE",
            TEMPLATE_TAGS,
            PARAMETERS_WITH_SERVICES,
            "UTC",
        )
        assertEquals(ERROR_FALLBACK_RENDERED, Directive.parse(rendered).promQl)
        val compiled = QueryCompiler.compile(rendered, emptyMap(), config, FIXED_CLOCK)
        val result = PrometheusClient(config, "zero-contract").use { it.execute(compiled) }

        assertEquals("POST", captured.get()?.method)
        assertEquals("/prometheus/api/v1/query", captured.get()?.path)
        assertEquals(ERROR_FALLBACK_RENDERED, captured.get()?.formParameters()?.first()?.second)
        if (scenario.expectedValue == null) {
            assertEquals(emptyList<List<Any?>>(), result.rows)
        } else {
            assertEquals(scenario.expectedValue, result.rows.single().last())
        }
    }

    @Test
    fun `keeps adversarial multi-select values inside their PromQL and HTTP contexts`() =
        withServer(successVectorResponse(emptyMap(), 1.0)) { server, captured ->
            val config = contractConfig(server)
            val parameters = listOf(
                mapOf(
                    "type" to "string/=",
                    "target" to listOf("variable", listOf("template-tag", "service")),
                    "value" to listOf("catalog\")|.*", "checkout\\blue"),
                ),
                timeParameter(),
            )
            val rendered = MetabaseParameters.substitute(
                "# metabase-mimir mode=instant time={{time}}\n" +
                    "api_requests_per_second{[[service=~{{service}}]]}",
                TEMPLATE_TAGS,
                parameters,
                "UTC",
            )
            val compiled = QueryCompiler.compile(rendered, emptyMap(), config, FIXED_CLOCK)
            PrometheusClient(config, "escaping-contract").use { it.execute(compiled) }

            val expected =
                "api_requests_per_second{service=~\"(catalog\\\"\\\\)\\\\|\\\\.\\\\*|checkout\\\\\\\\blue)\"}"
            assertEquals(expected, compiled.promQl)
            assertEquals(expected, captured.get()?.formParameters()?.first()?.second)
        }

    private fun withServer(
        responseBody: String,
        test: (LocalServer, AtomicReference<CapturedRequest?>) -> Unit,
    ) {
        val captured = AtomicReference<CapturedRequest?>()
        LocalServer(responseBody, captured).use { test(it, captured) }
    }

    private fun contractConfig(server: LocalServer): DriverConfig = DriverConfig.from(
        mapOf(
            "url" to server.uri("/prometheus").toString(),
            "query-timeout" to "30s",
            "maximum-query-range" to "6h",
            "maximum-data-points" to 1_000,
            "minimum-range-step" to "60s",
        ),
    )

    data class QueryContract(
        val name: String,
        val mode: Directive.Mode,
        val promQlTemplate: String,
        val responseLabels: Map<String, String>,
        val sampleValue: Double,
        val label: String? = null,
    ) {
        val nativeQuery: String = when (mode) {
            Directive.Mode.INSTANT -> "# metabase-mimir mode=instant time={{time}}\n$promQlTemplate"
            Directive.Mode.RANGE -> "# metabase-mimir mode=range step=auto time={{time}}\n$promQlTemplate"
            Directive.Mode.LABEL_VALUES ->
                "# metabase-mimir mode=label-values label=${requireNotNull(label)} time={{time}}\n$promQlTemplate"
        }
        val renderedPromQl: String = renderServices(promQlTemplate)
        val finalPromQl: String = replaceReservedTokens(renderedPromQl)
        val method: String = if (mode == Directive.Mode.LABEL_VALUES) "GET" else "POST"
        val endpoint: String = when (mode) {
            Directive.Mode.INSTANT -> "/prometheus/api/v1/query"
            Directive.Mode.RANGE -> "/prometheus/api/v1/query_range"
            Directive.Mode.LABEL_VALUES -> "/prometheus/api/v1/label/${requireNotNull(label)}/values"
        }
        val expectedForm: List<Pair<String, String>> = when (mode) {
            Directive.Mode.INSTANT -> listOf(
                "query" to finalPromQl,
                "time" to END_EPOCH_SECONDS,
                "timeout" to "30s",
            )
            Directive.Mode.RANGE -> listOf(
                "query" to finalPromQl,
                "start" to START_EPOCH_SECONDS,
                "end" to END_EPOCH_SECONDS,
                "step" to "1m",
                "timeout" to "30s",
            )
            Directive.Mode.LABEL_VALUES -> listOf(
                "match[]" to finalPromQl,
                "start" to START_EPOCH_SECONDS,
                "end" to END_EPOCH_SECONDS,
            )
        }

        fun responseBody(): String = when (mode) {
            Directive.Mode.LABEL_VALUES -> """{"status":"success","data":["catalog","checkout"]}"""
            Directive.Mode.RANGE -> successMatrixResponse(responseLabels, sampleValue)
            Directive.Mode.INSTANT -> successVectorResponse(responseLabels, sampleValue)
        }

        fun assertConverted(result: NormalizedResult) {
            assertEquals(emptyList<String>(), result.warnings)
            if (mode == Directive.Mode.LABEL_VALUES) {
                assertEquals(listOf(QueryColumn("value", ColumnType.TEXT)), result.columns)
                assertEquals(listOf(listOf("catalog"), listOf("checkout")), result.rows)
                return
            }
            val labelNames = responseLabels.keys.sorted()
            assertEquals(listOf("timestamp", "series", "metric") + labelNames + "value", result.columns.map { it.name })
            val expectedTimestamp = if (mode == Directive.Mode.RANGE) START else END
            val canonical = if (responseLabels.isEmpty()) {
                "{}"
            } else {
                responseLabels.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
                    "$key=\"$value\""
                }
            }
            assertEquals(
                listOf(expectedTimestamp, canonical, null) + labelNames.map(responseLabels::get) + sampleValue,
                result.rows.single(),
            )
        }
    }

    data class FallbackScenario(
        val name: String,
        val errors: Double?,
        val requests: Double?,
        val expectedValue: Double?,
    ) {
        fun responseBody(): String {
            val selectedValue = errors ?: requests?.let { 0.0 }
            val result = selectedValue?.let {
                """[{"metric":{},"value":[$END_EPOCH_SECONDS,"$it"]}]"""
            } ?: "[]"
            return """{"status":"success","data":{"resultType":"vector","result":$result}}"""
        }
    }

    private class LocalServer(
        responseBody: String,
        captured: AtomicReference<CapturedRequest?>,
    ) : AutoCloseable {
        private val executor: ExecutorService = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@LocalServer.executor
            createContext("/prometheus") { exchange ->
                captured.set(exchange.capture())
                exchange.respondJson(responseBody)
            }
            start()
        }

        fun uri(path: String) = java.net.URI("http://127.0.0.1:${server.address.port}$path")

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    internal data class CapturedRequest(
        val method: String,
        val path: String,
        val rawQuery: String?,
        val body: String,
    ) {
        fun formParameters(): List<Pair<String, String>> = decodeForm(rawQuery ?: body)
    }

    companion object {
        private val START = Instant.parse("2026-01-01T00:00:00Z")
        private val END = Instant.parse("2026-01-01T06:00:00Z")
        private val FIXED_CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        private val START_EPOCH_SECONDS = START.epochSecond.toString()
        private val END_EPOCH_SECONDS = END.epochSecond.toString()
        private const val DATE_RANGE = "2026-01-01T00:00:00Z~2026-01-01T06:00:00Z"
        private const val SERVICE_REGEX = "service=~\"(catalog|checkout)\""
        private const val INTERVAL = "\$__interval"
        private const val RANGE = "\$__range"
        private const val START_TOKEN = "\$__start"
        private const val END_TOKEN = "\$__end"
        private val RESERVED_TOKENS = listOf(INTERVAL, RANGE, START_TOKEN, END_TOKEN)

        private val TEMPLATE_TAGS = mapOf(
            "service" to mapOf("name" to "service", "type" to "text"),
            "time" to mapOf("name" to "time", "type" to "dimension"),
        )
        private val PARAMETERS_WITHOUT_SERVICES = listOf(
            timeParameter(),
        )
        private val PARAMETERS_WITH_SERVICES = listOf(
            serviceParameter(),
            timeParameter(),
        )

        private const val ERROR_FALLBACK_TEMPLATE =
            "sum(api_errors_per_second{status=~\"5..\"[[, service=~{{service}}]]}) or " +
                "(0 * sum(api_requests_per_second{[[service=~{{service}}]]}))"
        private const val ERROR_FALLBACK_RENDERED =
            "sum(api_errors_per_second{status=~\"5..\", service=~\"(catalog|checkout)\"}) or " +
                "(0 * sum(api_requests_per_second{service=~\"(catalog|checkout)\"}))"

        private fun successVectorResponse(labels: Map<String, String>, value: Double): String = Json.stringify(
            mapOf(
                "status" to "success",
                "data" to mapOf(
                    "resultType" to "vector",
                    "result" to listOf(
                        mapOf(
                            "metric" to labels,
                            "value" to listOf(END.epochSecond, value.toString()),
                        ),
                    ),
                ),
            ),
        )

        private fun successMatrixResponse(labels: Map<String, String>, value: Double): String = Json.stringify(
            mapOf(
                "status" to "success",
                "data" to mapOf(
                    "resultType" to "matrix",
                    "result" to listOf(
                        mapOf(
                            "metric" to labels,
                            "values" to listOf(listOf(START.epochSecond, value.toString())),
                        ),
                    ),
                ),
            ),
        )

        @JvmStatic
        fun dashboardQueries(): List<Arguments> = queryContracts().map { contract ->
            Arguments.of(Named.named(contract.name, contract.name), contract)
        }

        @JvmStatic
        fun optionalFilterQueries(): List<Arguments> = listOf(
            Arguments.of(
                "sole optional matcher",
                "api_requests_per_second{[[service=~{{service}}]]}",
                "api_requests_per_second{}",
            ),
            Arguments.of(
                "optional matcher after a required matcher",
                "api_errors_per_second{status=~\"5..\"[[, service=~{{service}}]]}",
                "api_errors_per_second{status=~\"5..\"}",
            ),
        )

        @JvmStatic
        fun fallbackScenarios(): List<Arguments> = listOf(
            FallbackScenario("requests and errors return the error value", 3.0, 20.0, 3.0),
            FallbackScenario("requests without errors return zero", null, 20.0, 0.0),
            FallbackScenario("no requests and no errors return no data", null, null, null),
        ).map { scenario -> Arguments.of(Named.named(scenario.name, scenario.name), scenario) }

        private fun queryContracts(): List<QueryContract> = listOf(
            labelValues(
                "filter values",
                """{__name__="api_requests_per_second"}""",
            ),
            instant(
                "current request rate",
                """sum(api_requests_per_second{[[service=~{{service}}]]})""",
            ),
            instant(
                "current server-error rate",
                """sum(api_errors_per_second{status=~"5.."[[, service=~{{service}}]]})""",
            ),
            instant(
                "current client-error rate excluding policy statuses",
                """
                sum(
                  api_errors_per_second{
                    status=~"4..",
                    status!~"412|417|451|459"[[, service=~{{service}}]]
                  }
                )
                """.trimIndent(),
            ),
            instant(
                "current policy-response rate",
                """
                sum(
                  api_errors_per_second{
                    status=~"412|417|451|459"[[, service=~{{service}}]]
                  }
                )
                """.trimIndent(),
            ),
            instant("range p95 latency", percentileQuery("0.95")),
            instant("range p99 latency", percentileQuery("0.99")),
            range(
                "request-rate trend by status class",
                """
                sum by (status_class) (
                  api_requests_per_second{[[service=~{{service}}]]}
                )
                """.trimIndent(),
                mapOf("status_class" to "2xx"),
            ),
            range("latency percentile trend p50", percentileQuery("0.5")),
            range("latency percentile trend p95", percentileQuery("0.95")),
            range("latency percentile trend p99", percentileQuery("0.99")),
            range(
                "mean latency trend",
                """
                sum(
                  sum_over_time(
                    api_latency_milliseconds_sum{[[service=~{{service}}]]}[${'$'}__range]
                  )
                )
                /
                sum(
                  sum_over_time(
                    api_latency_milliseconds_bucket{
                      le="+Inf"[[, service=~{{service}}]]
                    }[${'$'}__range]
                  )
                )
                """.trimIndent(),
            ),
            range(
                "request-rate trend by service",
                """
                sum by (service) (
                  api_requests_per_second{[[service=~{{service}}]]}
                )
                """.trimIndent(),
                mapOf("service" to "catalog"),
            ),
            range(
                "p95 latency by service",
                """
                histogram_quantile(
                  0.95,
                  sum by (service, le) (
                    sum_over_time(
                      api_latency_milliseconds_bucket{[[service=~{{service}}]]}[${'$'}__range]
                    )
                  )
                )
                """.trimIndent(),
                mapOf("service" to "catalog"),
            ),
            instant(
                "top routes by current request rate",
                """
                topk(
                  10,
                  sum by (route) (
                    api_requests_per_second{[[service=~{{service}}]]}
                  )
                )
                """.trimIndent(),
                mapOf("route" to "/items"),
            ),
            instant(
                "top routes by range p99 latency",
                """
                topk(
                  10,
                  histogram_quantile(
                    0.99,
                    sum by (route, le) (
                      sum_over_time(
                        api_latency_milliseconds_bucket{[[service=~{{service}}]]}[${'$'}__range]
                      )
                    )
                  )
                )
                """.trimIndent(),
                mapOf("route" to "/items"),
            ),
        ).also { contracts ->
            check(contracts.size == 16) { "The 14 dashboard shapes expand to 16 percentile contracts" }
        }

        private fun percentileQuery(percentile: String): String = """
            histogram_quantile(
              $percentile,
              sum by (le) (
                sum_over_time(
                  api_latency_milliseconds_bucket{[[service=~{{service}}]]}[${'$'}__range]
                )
              )
            )
        """.trimIndent()

        private fun instant(
            name: String,
            query: String,
            labels: Map<String, String> = emptyMap(),
        ) = QueryContract(
            name,
            Directive.Mode.INSTANT,
            query,
            labels,
            1.0,
        )

        private fun range(
            name: String,
            query: String,
            labels: Map<String, String> = emptyMap(),
        ) = QueryContract(
            name,
            Directive.Mode.RANGE,
            query,
            labels,
            2.0,
        )

        private fun labelValues(name: String, query: String) = QueryContract(
            name,
            Directive.Mode.LABEL_VALUES,
            query,
            emptyMap(),
            0.0,
            "service",
        )

        private fun renderServices(value: String): String = value
            .replace("[[service=~{{service}}]]", SERVICE_REGEX)
            .replace("[[, service=~{{service}}]]", ", $SERVICE_REGEX")

        private fun replaceReservedTokens(value: String): String = value
            .replace(RANGE, "6h")
            .replace(INTERVAL, "1m")
            .replace(START_TOKEN, START_EPOCH_SECONDS)
            .replace(END_TOKEN, END_EPOCH_SECONDS)

        private fun serviceParameter(): Map<String, Any> = mapOf(
            "type" to "string/=",
            "target" to listOf("variable", listOf("template-tag", "service")),
            "value" to listOf("catalog", "checkout"),
        )

        private fun timeParameter(): Map<String, Any> = mapOf(
            "type" to "date/range",
            "target" to listOf("dimension", listOf("template-tag", "time")),
            "value" to DATE_RANGE,
        )

        private fun decodeForm(value: String): List<Pair<String, String>> {
            if (value.isEmpty()) return emptyList()
            return value.split('&').map { entry ->
                val parts = entry.split('=', limit = 2)
                URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to
                    URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8)
            }
        }
    }
}

private fun HttpExchange.capture(): DashboardQueryContractTest.CapturedRequest =
    DashboardQueryContractTest.CapturedRequest(
        requestMethod,
        requestURI.path,
        requestURI.rawQuery,
        requestBody.use { it.readAllBytes().toString(StandardCharsets.UTF_8) },
    )

private fun HttpExchange.respondJson(body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(200, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
