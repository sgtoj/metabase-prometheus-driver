package io.cruxstack.metabase.prometheus

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object SyntheticMetabaseContractMain {
    private const val EMAIL = "admin@example.test"
    private const val PASSWORD = "SyntheticContract123!"
    private const val METABASE_VERSION = "v0.63.2"
    private const val FIXTURE_PORT = 18_081
    private const val MAIN_QUERY_TIMEOUT = "750ms"
    private const val CANCELLATION_QUERY_TIMEOUT = "30s"
    private const val CONNECTION_PROBE_TIMEOUT = "5s"

    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val end = Instant.parse("2026-01-01T06:00:00Z")
    private val step = Duration.ofSeconds(60)
    private val startEpoch = start.epochSecond
    private val endEpoch = end.epochSecond
    private val expectedRangeRows = (Duration.between(start, end).seconds / step.seconds + 1).toInt()

    private const val INSTANT_PROMQL =
        "api_requests_per_second{service=\"catalog\",route=\"/items\",status=\"200\",status_class=\"2xx\"}"
    private const val RANGE_PROMQL =
        "api_latency_milliseconds_bucket{service=\"catalog\",route=\"/items\",le=\"250\"}"
    private const val LABEL_VALUES_PROMQL = "api_requests_per_second{route=\"/items\"}"
    private const val DATE_RANGE_PROMQL =
        "api_latency_milliseconds_sum{service=\"catalog\",route=\"/items\"}"
    private const val OPTIONAL_EMPTY_PROMQL = "api_requests_per_second{route=\"/checkout\"}"
    private const val OPTIONAL_SINGLE_PROMQL =
        "api_requests_per_second{route=\"/checkout\",service=~\"(catalog)\"}"
    private const val OPTIONAL_MULTIPLE_PROMQL =
        "api_requests_per_second{route=\"/checkout\",service=~\"(catalog|checkout)\"}"
    private const val EMPTY_PROMQL =
        "api_errors_per_second{service=\"catalog\",status=\"204\",status_class=\"2xx\"}"
    private const val ZERO_PROMQL =
        "api_errors_per_second{service=\"catalog\",route=\"/zero\",status=\"200\",status_class=\"2xx\"}"
    private const val TIMEOUT_PROMQL =
        "api_errors_per_second{service=\"catalog\",route=\"/slow\",status=\"504\",status_class=\"5xx\"}"
    private const val BACKEND_ERROR_PROMQL =
        "api_errors_per_second{service=\"catalog\",route=\"/failure\",status=\"500\",status_class=\"5xx\"}"
    private const val CANCELLATION_PROMQL =
        "api_errors_per_second{service=\"catalog\",route=\"/cancel\",status=\"503\",status_class=\"5xx\"}"

    private val instantMetadata = listOf(
        ColumnMetadata("timestamp", "timestamp", "type/DateTime", "timestamp"),
        ColumnMetadata("series", "series", "type/Text", "text"),
        ColumnMetadata("metric", "metric", "type/Text", "text"),
        ColumnMetadata("route", "route", "type/Text", "text"),
        ColumnMetadata("service", "service", "type/Text", "text"),
        ColumnMetadata("status", "status", "type/Text", "text"),
        ColumnMetadata("status_class", "status_class", "type/Text", "text"),
        ColumnMetadata("value", "value", "type/Float", "double"),
    )
    private val rangeMetadata = listOf(
        ColumnMetadata("timestamp", "timestamp", "type/DateTime", "timestamp"),
        ColumnMetadata("series", "series", "type/Text", "text"),
        ColumnMetadata("metric", "metric", "type/Text", "text"),
        ColumnMetadata("le", "le", "type/Text", "text"),
        ColumnMetadata("route", "route", "type/Text", "text"),
        ColumnMetadata("service", "service", "type/Text", "text"),
        ColumnMetadata("value", "value", "type/Float", "double"),
    )
    private val dateRangeMetadata = listOf(
        ColumnMetadata("timestamp", "timestamp", "type/DateTime", "timestamp"),
        ColumnMetadata("series", "series", "type/Text", "text"),
        ColumnMetadata("metric", "metric", "type/Text", "text"),
        ColumnMetadata("route", "route", "type/Text", "text"),
        ColumnMetadata("service", "service", "type/Text", "text"),
        ColumnMetadata("value", "value", "type/Float", "double"),
    )
    private val labelValuesMetadata = listOf(
        ColumnMetadata("value", "value", "type/Text", "text"),
    )
    private val emptyMetadata = listOf(
        ColumnMetadata("timestamp", "timestamp", "type/DateTime", "timestamp"),
        ColumnMetadata("series", "series", "type/Text", "text"),
        ColumnMetadata("metric", "metric", "type/Text", "text"),
        ColumnMetadata("value", "value", "type/Float", "double"),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty()) { "metabaseSyntheticContract does not accept arguments" }
        val metabaseUrl = System.getenv("METABASE_URL") ?: "http://metabase:3000"
        val advertisedBackendUrl = System.getenv("SYNTHETIC_BACKEND_URL")
            ?: "http://toolchain:$FIXTURE_PORT/prometheus"
        val api = MetabaseApi(metabaseUrl)

        SyntheticPrometheusFixture(advertisedBackendUrl).use { fixture ->
            api.waitUntilHealthy()
            val properties = api.getObject("/api/session/properties")
            assertMetabaseVersionAndDriver(properties)
            val session = api.createSession(properties)
            val databaseId = api.createDatabase(
                session,
                "Synthetic Prometheus",
                advertisedBackendUrl,
                MAIN_QUERY_TIMEOUT,
            )
            api.assertDatabaseHealthy(session, databaseId)
            val timestampFieldId = api.findTimestampFieldId(session, databaseId)

            val instantQuery = fixedInstantQuery(databaseId, INSTANT_PROMQL)
            val instantResult = api.executeCompleted(session, instantQuery, expectedRows = 1)
            instantResult.assertMetadata(instantMetadata)
            instantResult.assertText(0, "service", "catalog")
            instantResult.assertText(0, "route", "/items")
            instantResult.assertText(0, "status", "200")
            instantResult.assertNumber(0, "value", 125.5)

            val rangeQuery = fixedRangeQuery(databaseId, RANGE_PROMQL)
            val rangeResult = api.executeCompleted(session, rangeQuery, expectedRangeRows)
            rangeResult.assertMetadata(rangeMetadata)
            rangeResult.assertText(0, "le", "250")
            rangeResult.assertText(0, "service", "catalog")
            rangeResult.assertNumber(0, "value", 10.0)
            rangeResult.assertNumber(expectedRangeRows - 1, "value", 370.0)

            val labelValuesQuery = fixedLabelValuesQuery(databaseId)
            val labelValuesResult = api.executeCompleted(session, labelValuesQuery, expectedRows = 2)
            labelValuesResult.assertMetadata(labelValuesMetadata)
            check(labelValuesResult.textColumn("value") == listOf("catalog", "checkout")) {
                "Label-values query did not return the exact deterministic services"
            }

            val dateRangeQuery = dateRangeQuery(databaseId, timestampFieldId)
            val dateRangeResult = api.executeCompleted(session, dateRangeQuery, expectedRangeRows)
            dateRangeResult.assertMetadata(dateRangeMetadata)
            dateRangeResult.assertNumber(0, "value", 1_000.0)
            dateRangeResult.assertNumber(expectedRangeRows - 1, "value", 1_360.0)

            val optionalEmpty = api.executeCompleted(
                session,
                optionalServiceQuery(databaseId, null),
                expectedRows = 2,
            )
            check(optionalEmpty.textColumn("service") == listOf("catalog", "checkout")) {
                "Missing optional service filter did not return both services"
            }
            val optionalSingle = api.executeCompleted(
                session,
                optionalServiceQuery(databaseId, listOf("catalog")),
                expectedRows = 1,
            )
            check(optionalSingle.textColumn("service") == listOf("catalog")) {
                "Single optional service filter was not applied"
            }
            val optionalMultiple = api.executeCompleted(
                session,
                optionalServiceQuery(databaseId, listOf("catalog", "checkout")),
                expectedRows = 2,
            )
            check(optionalMultiple.textColumn("service") == listOf("catalog", "checkout")) {
                "Multiple optional service filters were not applied"
            }

            val emptyResult = api.executeCompleted(
                session,
                fixedInstantQuery(databaseId, EMPTY_PROMQL),
                expectedRows = 0,
            )
            check(emptyResult.rows.isEmpty()) { "Empty Prometheus result must produce exactly zero Metabase rows" }
            emptyResult.assertMetadata(emptyMetadata)

            val zeroResult = api.executeCompleted(
                session,
                fixedInstantQuery(databaseId, ZERO_PROMQL),
                expectedRows = 1,
            )
            zeroResult.assertMetadata(instantMetadata)
            zeroResult.assertNumber(0, "value", 0.0)

            val cards = listOf(
                api.createCard(
                    session,
                    "Synthetic request rate",
                    "scalar",
                    instantQuery,
                    mapOf("scalar.field" to "value"),
                ),
                api.createCard(
                    session,
                    "Synthetic latency buckets",
                    "line",
                    rangeQuery,
                    mapOf("graph.dimensions" to listOf("timestamp"), "graph.metrics" to listOf("value")),
                ),
                api.createCard(
                    session,
                    "Synthetic services",
                    "table",
                    labelValuesQuery,
                    emptyMap(),
                ),
            )
            api.executeCard(session, cards[0], expectedRows = 1).assertMetadata(instantMetadata)
            api.executeCard(session, cards[1], expectedRows = expectedRangeRows).assertMetadata(rangeMetadata)
            api.executeCard(session, cards[2], expectedRows = 2).assertMetadata(labelValuesMetadata)
            api.assertCardCsvExport(session, cards[0])

            api.executeFailed(
                session,
                fixedInstantQuery(databaseId, TIMEOUT_PROMQL),
                expectedText = listOf("timed out", "timeout"),
            )
            api.executeCompleted(session, instantQuery, expectedRows = 1)
            api.assertHealthy()

            api.executeFailed(
                session,
                fixedInstantQuery(databaseId, BACKEND_ERROR_PROMQL),
                expectedText = listOf("synthetic backend failure"),
            )
            api.executeCompleted(session, instantQuery, expectedRows = 1)
            api.assertHealthy()

            val cancellationDatabaseId = api.createDatabase(
                session,
                "Synthetic Prometheus Cancellation",
                advertisedBackendUrl,
                CANCELLATION_QUERY_TIMEOUT,
            )
            api.assertDatabaseHealthy(session, cancellationDatabaseId)
            val cancellation = api.executeAsync(
                session,
                fixedInstantQuery(cancellationDatabaseId, CANCELLATION_PROMQL),
            )
            fixture.awaitCancellationStreamStarted()
            val canceledAt = System.nanoTime()
            check(cancellation.cancel(true)) { "Metabase cancellation request completed before it could be canceled" }
            fixture.awaitCancellationStreamClosed()
            val cancellationMillis = (fixture.cancellationStreamClosedAt() - canceledAt) / 1_000_000
            check(cancellationMillis in 0 until Duration.ofSeconds(10).toMillis()) {
                "Backend cancellation stream closed after ${cancellationMillis}ms"
            }
            api.executeCompleted(session, instantQuery, expectedRows = 1)
            api.assertHealthy()

            fixture.assertContract()
            println("Synthetic Metabase packaged-driver contract passed for database $databaseId")
        }
    }

    private fun assertMetabaseVersionAndDriver(properties: JsonValue.ObjectValue) {
        val version = properties["version"]?.requireObject("Metabase version")
            ?.get("tag")?.requireString("Metabase version tag")
            ?: error("Metabase session properties did not contain a version tag")
        check(version == METABASE_VERSION) { "Expected Metabase $METABASE_VERSION but found $version" }
        val engines = properties["engines"]?.requireObject("Metabase engines")
            ?: error("Metabase session properties did not contain engines")
        check(engines["prometheus"] != null) { "Packaged prometheus driver was not registered" }
    }

    private fun fixedInstantQuery(databaseId: Int, promQl: String): Map<String, Any?> = datasetQuery(
        databaseId,
        "# metabase-mimir mode=instant time=@$start/$end\n$promQl",
    )

    private fun fixedRangeQuery(databaseId: Int, promQl: String): Map<String, Any?> = datasetQuery(
        databaseId,
        "# metabase-mimir mode=range step=auto time=@$start/$end\n$promQl",
    )

    private fun fixedLabelValuesQuery(databaseId: Int): Map<String, Any?> = datasetQuery(
        databaseId,
        "# metabase-mimir mode=label-values time=@$start/$end label=service\n$LABEL_VALUES_PROMQL",
    )

    private fun dateRangeQuery(databaseId: Int, timestampFieldId: Int): Map<String, Any?> = mapOf(
        "database" to databaseId,
        "type" to "native",
        "native" to mapOf(
            "query" to "# metabase-mimir mode=range step=auto time={{time}}\n$DATE_RANGE_PROMQL",
            "template-tags" to mapOf(
                "time" to mapOf(
                    "id" to "time-tag",
                    "name" to "time",
                    "display-name" to "Time",
                    "type" to "dimension",
                    "widget-type" to "date/all-options",
                    "dimension" to listOf("field", timestampFieldId, null),
                ),
            ),
        ),
        "parameters" to listOf(
            mapOf(
                "type" to "date/range",
                "target" to listOf("dimension", listOf("template-tag", "time")),
                "value" to "$start~$end",
            ),
        ),
    )

    private fun optionalServiceQuery(databaseId: Int, services: List<String>?): Map<String, Any?> = mapOf(
        "database" to databaseId,
        "type" to "native",
        "native" to mapOf(
            "query" to "# metabase-mimir mode=instant time=@$start/$end\n" +
                "api_requests_per_second{route=\"/checkout\"[[,service=~{{service}}]]}",
            "template-tags" to mapOf(
                "service" to mapOf(
                    "id" to "service-tag",
                    "name" to "service",
                    "display-name" to "Service",
                    "type" to "text",
                    "required" to false,
                ),
            ),
        ),
        "parameters" to if (services == null) {
            emptyList<Any>()
        } else {
            listOf(
                mapOf(
                    "type" to "string/=",
                    "target" to listOf("variable", listOf("template-tag", "service")),
                    "value" to services,
                ),
            )
        },
    )

    private fun datasetQuery(databaseId: Int, query: String): Map<String, Any?> = mapOf(
        "database" to databaseId,
        "type" to "native",
        "native" to mapOf("query" to query, "template-tags" to emptyMap<String, Any>()),
        "parameters" to emptyList<Any>(),
    )

    private data class ColumnMetadata(
        val name: String,
        val displayName: String,
        val baseType: String,
        val databaseType: String,
    )

    private data class DatasetResult(
        val columns: List<ColumnMetadata>,
        val rows: List<JsonValue.ArrayValue>,
    ) {
        fun assertMetadata(expected: List<ColumnMetadata>) {
            check(columns == expected) { "Unexpected Metabase result metadata: $columns" }
        }

        fun assertText(row: Int, column: String, expected: String) {
            val actual = value(row, column).requireString("$column value")
            check(actual == expected) { "Expected $column=$expected but found $actual" }
        }

        fun assertNumber(row: Int, column: String, expected: Double) {
            val actual = (value(row, column) as? JsonValue.NumberValue)?.value?.toDouble()
                ?: error("$column value was not numeric")
            check(actual == expected) { "Expected $column=$expected but found $actual" }
        }

        fun textColumn(column: String): List<String> = rows.indices.map { row ->
            value(row, column).requireString("$column value")
        }

        private fun value(row: Int, column: String): JsonValue {
            val index = columns.indexOfFirst { it.name == column }
            check(index >= 0) { "Metabase result did not contain column $column" }
            return rows[row].values[index]
        }
    }

    private data class SavedCard(val id: Int, val display: String)

    private class MetabaseApi(private val baseUrl: String) {
        private val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        fun waitUntilHealthy() {
            val deadline = monotonicDeadline(Duration.ofSeconds(180))
            var lastResponse = "Metabase did not respond"
            while (beforeDeadline(deadline)) {
                runCatching { request("GET", "/api/health", requireSuccess = false) }.onSuccess { response ->
                    lastResponse = response.body
                    if (response.statusCode in 200..299) {
                        val status = runCatching {
                            Json.parse(response.body).requireObject("Metabase health")["status"]
                                ?.requireString("health status")
                        }.getOrNull()
                        if (status == "ok") return
                    }
                }
                Thread.sleep(500)
            }
            error("Metabase did not become healthy: ${lastResponse.take(500)}")
        }

        fun assertHealthy() {
            val response = getObject("/api/health")
            check(response["status"]?.requireString("Metabase health status") == "ok") {
                "Metabase was not healthy after query failure"
            }
        }

        fun getObject(path: String, session: String? = null): JsonValue.ObjectValue =
            Json.parse(request("GET", path, session = session).body)
                .requireObject("Metabase response from $path")

        fun createSession(properties: JsonValue.ObjectValue): String {
            val hasUserSetup = (properties["has-user-setup"] as? JsonValue.BooleanValue)?.value ?: false
            return if (hasUserSetup) login() else {
                val setupToken = properties["setup-token"]?.requireString("Metabase setup token")
                    ?: error("Fresh Metabase instance did not expose a setup token")
                val response = postObject(
                    "/api/setup",
                    mapOf(
                        "token" to setupToken,
                        "prefs" to mapOf("site_name" to "Synthetic Prometheus Contract", "site_locale" to "en"),
                        "user" to mapOf(
                            "first_name" to "Contract",
                            "last_name" to "Admin",
                            "email" to EMAIL,
                            "password" to PASSWORD,
                        ),
                    ),
                )
                response["id"]?.requireString("Setup session ID")
                    ?: error("Metabase setup did not return a session")
            }
        }

        private fun login(): String {
            val response = postObject("/api/session", mapOf("username" to EMAIL, "password" to PASSWORD))
            return response["id"]?.requireString("Login session ID")
                ?: error("Metabase login did not return a session")
        }

        fun createDatabase(session: String, name: String, backendUrl: String, queryTimeout: String): Int {
            val response = postObject(
                "/api/database",
                mapOf(
                    "name" to name,
                    "engine" to "prometheus",
                    "details" to mapOf(
                        "url" to backendUrl,
                        "auth-mode" to "none",
                        "connect-timeout" to "2s",
                        "query-timeout" to queryTimeout,
                        "maximum-query-range" to "1d",
                        "maximum-data-points" to 500,
                        "minimum-range-step" to "${step.seconds}s",
                    ),
                ),
                session,
            )
            return response.requireInt("id")
        }

        fun assertDatabaseHealthy(session: String, databaseId: Int) {
            val response = getObject("/api/database/$databaseId/healthcheck", session)
            check(response["status"]?.requireString("database health status") == "ok") {
                "Synthetic Prometheus database health check failed"
            }
        }

        fun findTimestampFieldId(session: String, databaseId: Int): Int {
            val deadline = monotonicDeadline(Duration.ofSeconds(60))
            var lastResponse = ""
            while (beforeDeadline(deadline)) {
                lastResponse = request("GET", "/api/database/$databaseId/metadata", session = session).body
                val metadata = runCatching {
                    Json.parse(lastResponse).requireObject("Database metadata")
                }.getOrNull()
                val tables = (metadata?.get("tables") as? JsonValue.ArrayValue)?.values.orEmpty()
                val queryContext = tables.mapNotNull { it as? JsonValue.ObjectValue }
                    .firstOrNull { (it["name"] as? JsonValue.StringValue)?.value == "query_context" }
                val fields = (queryContext?.get("fields") as? JsonValue.ArrayValue)?.values.orEmpty()
                val timestamp = fields.mapNotNull { it as? JsonValue.ObjectValue }
                    .firstOrNull { (it["name"] as? JsonValue.StringValue)?.value == "timestamp" }
                if (timestamp != null) return timestamp.requireInt("id")
                Thread.sleep(500)
            }
            error("Virtual query_context.timestamp field was not synchronized: ${lastResponse.take(1_000)}")
        }

        fun executeCompleted(
            session: String,
            query: Map<String, Any?>,
            expectedRows: Int,
        ): DatasetResult {
            val response = postObject("/api/dataset", query, session)
            val status = response["status"]?.requireString("dataset status")
            check(status == "completed") { "Metabase dataset query did not complete: $response" }
            val data = response["data"]?.requireObject("dataset data")
                ?: error("Completed Metabase dataset response did not contain data")
            val rows = data["rows"]?.requireArray("dataset rows")?.values.orEmpty()
                .map { it.requireArray("dataset row") }
            check(rows.size == expectedRows) {
                "Expected exactly $expectedRows Metabase row(s), found ${rows.size}"
            }
            val columns = data["cols"]?.requireArray("dataset columns")?.values.orEmpty().map { rawColumn ->
                val column = rawColumn.requireObject("dataset column")
                ColumnMetadata(
                    name = column["name"]?.requireString("column name") ?: error("Column is missing name"),
                    displayName = column["display_name"]?.requireString("column display_name")
                        ?: error("Column is missing display_name"),
                    baseType = column["base_type"]?.requireString("column base_type")
                        ?: error("Column is missing base_type"),
                    databaseType = column["database_type"]?.requireString("column database_type")
                        ?: error("Column is missing database_type"),
                )
            }
            return DatasetResult(columns, rows)
        }

        fun executeFailed(session: String, query: Map<String, Any?>, expectedText: List<String>) {
            val response = request(
                "POST",
                "/api/dataset",
                Json.stringify(query),
                session,
                requireSuccess = false,
            )
            val parsed = Json.parse(response.body).requireObject("failed Metabase dataset response")
            val status = parsed["status"]?.requireString("failed dataset status")
            check(status == "failed") {
                "Expected a failed Metabase dataset response, got HTTP ${response.statusCode}: ${response.body.take(2_000)}"
            }
            val lowercaseBody = response.body.lowercase()
            check(expectedText.any { it in lowercaseBody }) {
                "Failed Metabase response did not contain one of $expectedText: ${response.body.take(2_000)}"
            }
        }

        fun createCard(
            session: String,
            name: String,
            display: String,
            query: Map<String, Any?>,
            visualizationSettings: Map<String, Any?>,
        ): SavedCard {
            val response = postObject(
                "/api/card",
                mapOf(
                    "name" to name,
                    "type" to "question",
                    "display" to display,
                    "dataset_query" to query,
                    "visualization_settings" to visualizationSettings,
                    "parameters" to emptyList<Any>(),
                ),
                session,
            )
            val actualDisplay = response["display"]?.requireString("saved card display")
            check(actualDisplay == display) { "Saved card $name has display $actualDisplay instead of $display" }
            val actualSettings = response["visualization_settings"]?.requireObject("saved card settings")
                ?: error("Saved card $name did not return visualization settings")
            check(actualSettings == Json.parse(Json.stringify(visualizationSettings))) {
                "Saved card $name did not preserve its visualization settings"
            }
            return SavedCard(response.requireInt("id"), display)
        }

        fun executeCard(session: String, card: SavedCard, expectedRows: Int): DatasetResult {
            check(card.display in setOf("scalar", "line", "table")) { "Unexpected saved card display: ${card.display}" }
            val response = postObject(
                "/api/card/${card.id}/query",
                mapOf("parameters" to emptyList<Any>(), "ignore_cache" to true),
                session,
            )
            val status = response["status"]?.requireString("saved card query status")
            check(status == "completed") { "Saved ${card.display} card did not execute: $response" }
            val data = response["data"]?.requireObject("saved card data")
                ?: error("Saved card response did not contain data")
            val rows = data["rows"]?.requireArray("saved card rows")?.values.orEmpty()
                .map { it.requireArray("saved card row") }
            check(rows.size == expectedRows) {
                "Saved ${card.display} card returned ${rows.size} rows instead of $expectedRows"
            }
            val columns = data["cols"]?.requireArray("saved card columns")?.values.orEmpty().map { rawColumn ->
                val column = rawColumn.requireObject("saved card column")
                ColumnMetadata(
                    column["name"]?.requireString("column name") ?: error("Column is missing name"),
                    column["display_name"]?.requireString("column display_name")
                        ?: error("Column is missing display_name"),
                    column["base_type"]?.requireString("column base_type")
                        ?: error("Column is missing base_type"),
                    column["database_type"]?.requireString("column database_type")
                        ?: error("Column is missing database_type"),
                )
            }
            return DatasetResult(columns, rows)
        }

        fun assertCardCsvExport(session: String, card: SavedCard) {
            val response = request(
                "POST",
                "/api/card/${card.id}/query/csv",
                Json.stringify(
                    mapOf(
                        "parameters" to emptyList<Any>(),
                        "format_rows" to false,
                        "pivot_results" to false,
                        "csv_include_bom" to false,
                    ),
                ),
                session,
            )
            val header = response.body.lineSequence().firstOrNull().orEmpty()
            check(header.contains("timestamp") && header.contains("service") && header.contains("value")) {
                "Saved-card CSV export did not contain useful result columns: $header"
            }
            check(response.body.contains("catalog")) { "Saved-card CSV export did not contain its data row" }
        }

        fun executeAsync(
            session: String,
            query: Map<String, Any?>,
        ): CompletableFuture<HttpResponse<String>> {
            val request = requestBuilder("POST", "/api/dataset", Json.stringify(query), session)
                .timeout(Duration.ofSeconds(45))
                .build()
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        }

        private fun postObject(
            path: String,
            body: Map<String, Any?>,
            session: String? = null,
        ): JsonValue.ObjectValue = Json.parse(request("POST", path, Json.stringify(body), session).body)
            .requireObject("Metabase response from $path")

        private fun request(
            method: String,
            path: String,
            body: String? = null,
            session: String? = null,
            requireSuccess: Boolean = true,
        ): ApiResponse {
            val request = requestBuilder(method, path, body, session)
                .timeout(Duration.ofSeconds(90))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (requireSuccess) {
                check(response.statusCode() in 200..299) {
                    "$method $path returned HTTP ${response.statusCode()}: ${response.body().take(2_000)}"
                }
            }
            return ApiResponse(response.statusCode(), response.body())
        }

        private fun requestBuilder(
            method: String,
            path: String,
            body: String?,
            session: String?,
        ): HttpRequest.Builder {
            val builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Accept", "application/json")
            if (session != null) builder.header("X-Metabase-Session", session)
            return if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody())
            } else {
                builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            }
        }

        private fun JsonValue.ObjectValue.requireInt(name: String): Int {
            val number = this[name] as? JsonValue.NumberValue
                ?: error("Metabase response is missing numeric $name")
            return number.value.intValueExact()
        }
    }

    private data class ApiResponse(val statusCode: Int, val body: String)

    private class SyntheticPrometheusFixture(private val advertisedUrl: String) : AutoCloseable {
        private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
        private val failures = ConcurrentLinkedQueue<String>()
        private val requests = CopyOnWriteArrayList<CapturedRequest>()
        private val requestCounts = ConcurrentHashMap<String, AtomicInteger>()
        private val cancellationStarted = CountDownLatch(1)
        private val cancellationClosed = CountDownLatch(1)
        private val cancellationClosedAt = AtomicLong(-1)
        private val server = HttpServer.create(InetSocketAddress("0.0.0.0", FIXTURE_PORT), 0).apply {
            this.executor = this@SyntheticPrometheusFixture.executor
            createContext("/") { exchange -> handle(exchange) }
            start()
        }

        init {
            check(advertisedUrl == "http://toolchain:$FIXTURE_PORT/prometheus") {
                "Synthetic backend must be advertised on the toolchain service alias"
            }
        }

        fun awaitCancellationStreamStarted() {
            check(cancellationStarted.await(10, TimeUnit.SECONDS)) {
                "Synthetic cancellation request did not reach the backend"
            }
        }

        fun awaitCancellationStreamClosed() {
            check(cancellationClosed.await(10, TimeUnit.SECONDS)) {
                "Metabase client disconnect did not close the synthetic backend stream within 10 seconds"
            }
        }

        fun cancellationStreamClosedAt(): Long = cancellationClosedAt.get().also {
            check(it >= 0) { "Synthetic cancellation stream did not record a close time" }
        }

        fun assertContract() {
            check(failures.isEmpty()) { "Synthetic backend contract violation(s): ${failures.joinToString("; ")}" }
            listOf(
                "build-info",
                "probe",
                "instant",
                "range",
                "label-values",
                "date-range",
                "optional-empty",
                "optional-single",
                "optional-multiple",
                "empty",
                "zero",
                "timeout",
                "backend-error",
                "cancellation",
            ).forEach { key ->
                check(requestCounts[key]?.get()?.let { it > 0 } == true) {
                    "Synthetic backend did not observe required request: $key"
                }
            }
            check(requests.all { it.path in allowedPaths }) {
                "Synthetic backend captured a request outside its read-only endpoint allowlist"
            }
        }

        private fun handle(exchange: HttpExchange) {
            try {
                validateHeaders(exchange)
                val form = when (exchange.requestMethod) {
                    "GET" -> parseForm(exchange.requestURI.rawQuery.orEmpty())
                    "POST" -> {
                        val contentType = exchange.requestHeaders.getFirst("Content-Type").orEmpty()
                        check(contentType.startsWith("application/x-www-form-urlencoded")) {
                            "POST request did not use form encoding: $contentType"
                        }
                        parseForm(exchange.requestBody.use { it.readAllBytes().toString(StandardCharsets.UTF_8) })
                    }
                    else -> error("Unexpected backend method ${exchange.requestMethod}")
                }
                val captured = CapturedRequest(exchange.requestMethod, exchange.requestURI.path, form.entries)
                requests += captured
                when (captured.path) {
                    "/prometheus/api/v1/status/buildinfo" -> handleBuildInfo(exchange, form)
                    "/prometheus/api/v1/query" -> handleInstant(exchange, form)
                    "/prometheus/api/v1/query_range" -> handleRange(exchange, form)
                    "/prometheus/api/v1/label/service/values" -> handleLabelValues(exchange, form)
                    else -> error("Unexpected backend endpoint ${captured.method} ${captured.path}")
                }
            } catch (exception: Throwable) {
                failures += "${exchange.requestMethod} ${exchange.requestURI}: ${exception.message ?: exception.javaClass.name}"
                runCatching {
                    exchange.respondJson(
                        400,
                        Json.stringify(
                            mapOf(
                                "status" to "error",
                                "errorType" to "bad_data",
                                "error" to "synthetic request contract violation: " +
                                    (exception.message ?: exception.javaClass.simpleName),
                            ),
                        ),
                    )
                }
            } finally {
                exchange.close()
            }
        }

        private fun validateHeaders(exchange: HttpExchange) {
            check(exchange.requestHeaders.getFirst("Authorization") == null) {
                "Synthetic backend request unexpectedly contained Authorization"
            }
            check(exchange.requestHeaders.getFirst("X-Scope-OrgID") == null) {
                "Synthetic backend request unexpectedly contained a tenant header"
            }
            val userAgent = exchange.requestHeaders.getFirst("User-Agent").orEmpty()
            check(
                Regex("metabase-prometheus-driver/(?!development)[^ ]+ (query|connection-test|version-check)")
                    .matches(userAgent),
            ) { "Backend request did not originate from the packaged driver: $userAgent" }
        }

        private fun handleBuildInfo(exchange: HttpExchange, form: FormData) {
            check(exchange.requestMethod == "GET") { "Build-info endpoint must use GET" }
            form.requireKeys()
            count("build-info")
            exchange.respondJson(
                200,
                Json.stringify(
                    mapOf(
                        "status" to "success",
                        "data" to mapOf("application" to "Prometheus", "version" to "synthetic-1"),
                    ),
                ),
            )
        }

        private fun handleInstant(exchange: HttpExchange, form: FormData) {
            check(exchange.requestMethod == "POST") { "Instant query endpoint must use POST" }
            form.requireKeys("query", "time", "timeout")
            val query = form.single("query")
            val timeout = form.single("timeout")
            if (query == "vector(1)") {
                check(timeout in setOf(CONNECTION_PROBE_TIMEOUT, MAIN_QUERY_TIMEOUT, CANCELLATION_QUERY_TIMEOUT)) {
                    "Connection probe used unexpected timeout $timeout"
                }
                form.single("time").toBigDecimalOrNull()
                    ?: error("Connection probe time was not an epoch value")
                count("probe")
                exchange.respondJson(200, vectorResponse(listOf(probeSeries), form.single("time").toBigDecimal()))
                return
            }

            check(form.single("time") == endEpoch.toString()) {
                "Instant query did not use the fixed end epoch"
            }
            when (query) {
                INSTANT_PROMQL -> {
                    check(timeout == MAIN_QUERY_TIMEOUT) { "Instant query used timeout $timeout" }
                    count("instant")
                    exchange.respondJson(200, vectorResponse(listOf(requestSeries), BigDecimal.valueOf(endEpoch)))
                }
                OPTIONAL_EMPTY_PROMQL -> {
                    check(timeout == MAIN_QUERY_TIMEOUT) { "Optional query used timeout $timeout" }
                    count("optional-empty")
                    exchange.respondJson(
                        200,
                        vectorResponse(listOf(optionalCatalogSeries, optionalCheckoutSeries), BigDecimal.valueOf(endEpoch)),
                    )
                }
                OPTIONAL_SINGLE_PROMQL -> {
                    check(timeout == MAIN_QUERY_TIMEOUT) { "Optional query used timeout $timeout" }
                    count("optional-single")
                    exchange.respondJson(
                        200,
                        vectorResponse(listOf(optionalCatalogSeries), BigDecimal.valueOf(endEpoch)),
                    )
                }
                OPTIONAL_MULTIPLE_PROMQL -> {
                    check(timeout == MAIN_QUERY_TIMEOUT) { "Optional query used timeout $timeout" }
                    count("optional-multiple")
                    exchange.respondJson(
                        200,
                        vectorResponse(listOf(optionalCatalogSeries, optionalCheckoutSeries), BigDecimal.valueOf(endEpoch)),
                    )
                }
                EMPTY_PROMQL -> {
                    check(timeout == MAIN_QUERY_TIMEOUT) { "Empty query used timeout $timeout" }
                    count("empty")
                    exchange.respondJson(200, vectorResponse(emptyList(), BigDecimal.valueOf(endEpoch)))
                }
                ZERO_PROMQL -> {
                    check(timeout == MAIN_QUERY_TIMEOUT) { "Zero query used timeout $timeout" }
                    count("zero")
                    exchange.respondJson(200, vectorResponse(listOf(zeroSeries), BigDecimal.valueOf(endEpoch)))
                }
                TIMEOUT_PROMQL -> {
                    check(timeout == MAIN_QUERY_TIMEOUT) { "Timeout query used timeout $timeout" }
                    count("timeout")
                    Thread.sleep(Duration.ofSeconds(3))
                    runCatching {
                        exchange.respondJson(200, vectorResponse(emptyList(), BigDecimal.valueOf(endEpoch)))
                    }
                }
                BACKEND_ERROR_PROMQL -> {
                    check(timeout == MAIN_QUERY_TIMEOUT) { "Backend-error query used timeout $timeout" }
                    count("backend-error")
                    exchange.respondJson(
                        503,
                        Json.stringify(
                            mapOf(
                                "status" to "error",
                                "errorType" to "execution",
                                "error" to "synthetic backend failure",
                            ),
                        ),
                    )
                }
                CANCELLATION_PROMQL -> {
                    check(timeout == CANCELLATION_QUERY_TIMEOUT) { "Cancellation query used timeout $timeout" }
                    count("cancellation")
                    streamUntilCanceled(exchange)
                }
                else -> error("Unexpected instant PromQL: $query")
            }
        }

        private fun handleRange(exchange: HttpExchange, form: FormData) {
            check(exchange.requestMethod == "POST") { "Range query endpoint must use POST" }
            form.requireKeys("query", "start", "end", "step", "timeout")
            check(form.single("start") == startEpoch.toString()) { "Range query used an unexpected start epoch" }
            check(form.single("end") == endEpoch.toString()) { "Range query used an unexpected end epoch" }
            check(form.single("step") == "1m") { "Range query did not use the fixed 60-second step" }
            check(form.single("timeout") == MAIN_QUERY_TIMEOUT) { "Range query used an unexpected timeout" }
            when (val query = form.single("query")) {
                RANGE_PROMQL -> {
                    count("range")
                    exchange.respondJson(200, matrixResponse(latencyBucketSeries, valueStart = 10))
                }
                DATE_RANGE_PROMQL -> {
                    count("date-range")
                    exchange.respondJson(200, matrixResponse(latencySumSeries, valueStart = 1_000))
                }
                else -> error("Unexpected range PromQL: $query")
            }
        }

        private fun handleLabelValues(exchange: HttpExchange, form: FormData) {
            check(exchange.requestMethod == "GET") { "Label-values endpoint must use GET" }
            form.requireKeys("match[]", "start", "end")
            check(form.single("match[]") == LABEL_VALUES_PROMQL) { "Label-values selector changed" }
            check(form.single("start") == startEpoch.toString()) { "Label-values query used an unexpected start" }
            check(form.single("end") == endEpoch.toString()) { "Label-values query used an unexpected end" }
            count("label-values")
            exchange.respondJson(
                200,
                Json.stringify(mapOf("status" to "success", "data" to listOf("catalog", "checkout"))),
            )
        }

        private fun streamUntilCanceled(exchange: HttpExchange) {
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 0)
            try {
                exchange.responseBody.use { output ->
                    output.write(
                        "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                            .toByteArray(StandardCharsets.UTF_8),
                    )
                    output.flush()
                    cancellationStarted.countDown()
                    val whitespace = " ".repeat(4_096).toByteArray(StandardCharsets.UTF_8)
                    val deadline = monotonicDeadline(Duration.ofSeconds(35))
                    while (beforeDeadline(deadline)) {
                        output.write(whitespace)
                        output.flush()
                        Thread.sleep(25)
                    }
                    error("Cancellation backend stream remained open past its long query timeout")
                }
            } catch (_: IOException) {
                cancellationClosedAt.compareAndSet(-1, System.nanoTime())
                cancellationClosed.countDown()
            }
        }

        private fun vectorResponse(series: List<SyntheticSeries>, timestamp: BigDecimal): String = Json.stringify(
            mapOf(
                "status" to "success",
                "data" to mapOf(
                    "resultType" to "vector",
                    "result" to series.map { item ->
                        mapOf(
                            "metric" to item.metricLabels(),
                            "value" to listOf(timestamp, item.value.toString()),
                        )
                    },
                ),
            ),
        )

        private fun matrixResponse(series: SyntheticSeries, valueStart: Int): String {
            val values = (0 until expectedRangeRows).map { index ->
                listOf(startEpoch + index * step.seconds, (valueStart + index).toString())
            }
            return Json.stringify(
                mapOf(
                    "status" to "success",
                    "data" to mapOf(
                        "resultType" to "matrix",
                        "result" to listOf(mapOf("metric" to series.metricLabels(), "values" to values)),
                    ),
                ),
            )
        }

        private fun SyntheticSeries.metricLabels(): Map<String, String> = buildMap {
            check(metric in metricNames) { "Synthetic response used unexpected metric $metric" }
            check(labels.keys.all { it in labelNames }) { "Synthetic response used unexpected labels ${labels.keys}" }
            put("__name__", metric)
            putAll(labels)
        }

        private fun count(name: String) {
            requestCounts.computeIfAbsent(name) { AtomicInteger() }.incrementAndGet()
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }

        private data class CapturedRequest(
            val method: String,
            val path: String,
            val form: List<Pair<String, String>>,
        )

        private data class SyntheticSeries(
            val metric: String,
            val labels: Map<String, String>,
            val value: BigDecimal,
        )

        private companion object {
            val allowedPaths = setOf(
                "/prometheus/api/v1/status/buildinfo",
                "/prometheus/api/v1/query",
                "/prometheus/api/v1/query_range",
                "/prometheus/api/v1/label/service/values",
            )
            val metricNames = setOf(
                "api_requests_per_second",
                "api_errors_per_second",
                "api_latency_milliseconds_bucket",
                "api_latency_milliseconds_sum",
            )
            val labelNames = setOf("service", "route", "status", "status_class", "le")
            val probeSeries = SyntheticSeries("api_requests_per_second", emptyMap(), BigDecimal.ONE)
            val requestSeries = SyntheticSeries(
                "api_requests_per_second",
                linkedMapOf(
                    "service" to "catalog",
                    "route" to "/items",
                    "status" to "200",
                    "status_class" to "2xx",
                ),
                BigDecimal("125.5"),
            )
            val optionalCatalogSeries = SyntheticSeries(
                "api_requests_per_second",
                linkedMapOf(
                    "service" to "catalog",
                    "route" to "/checkout",
                    "status" to "200",
                    "status_class" to "2xx",
                ),
                BigDecimal("80"),
            )
            val optionalCheckoutSeries = SyntheticSeries(
                "api_requests_per_second",
                linkedMapOf(
                    "service" to "checkout",
                    "route" to "/checkout",
                    "status" to "200",
                    "status_class" to "2xx",
                ),
                BigDecimal("60"),
            )
            val zeroSeries = SyntheticSeries(
                "api_errors_per_second",
                linkedMapOf(
                    "service" to "catalog",
                    "route" to "/zero",
                    "status" to "200",
                    "status_class" to "2xx",
                ),
                BigDecimal.ZERO,
            )
            val latencyBucketSeries = SyntheticSeries(
                "api_latency_milliseconds_bucket",
                linkedMapOf("service" to "catalog", "route" to "/items", "le" to "250"),
                BigDecimal.ZERO,
            )
            val latencySumSeries = SyntheticSeries(
                "api_latency_milliseconds_sum",
                linkedMapOf("service" to "catalog", "route" to "/items"),
                BigDecimal.ZERO,
            )
        }
    }

    private data class FormData(val entries: List<Pair<String, String>>) {
        fun requireKeys(vararg expected: String) {
            check(entries.map { it.first } == expected.toList()) {
                "Expected form fields ${expected.toList()}, found ${entries.map { it.first }}"
            }
            check(entries.map { it.first }.distinct().size == entries.size) { "Form contained duplicate fields" }
        }

        fun single(name: String): String = entries.singleOrNull { it.first == name }?.second
            ?: error("Form did not contain exactly one $name field")
    }

    private fun parseForm(raw: String): FormData {
        if (raw.isEmpty()) return FormData(emptyList())
        return FormData(
            raw.split('&').map { entry ->
                val pieces = entry.split('=', limit = 2)
                check(pieces.size == 2 && pieces[0].isNotEmpty()) { "Malformed form entry" }
                URLDecoder.decode(pieces[0], StandardCharsets.UTF_8) to
                    URLDecoder.decode(pieces[1], StandardCharsets.UTF_8)
            },
        )
    }

    private fun monotonicDeadline(timeout: Duration): Long = System.nanoTime() + timeout.toNanos()

    private fun beforeDeadline(deadline: Long): Boolean = deadline - System.nanoTime() > 0

    private fun HttpExchange.respondJson(status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.set("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
