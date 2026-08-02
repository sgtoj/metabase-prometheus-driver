package io.cruxstack.metabase.prometheus

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object MetabaseSmokeMain {
    private const val EMAIL = "admin@example.test"
    private const val PASSWORD = "MetabaseLocal123!"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty()) { "metabaseSmoke does not accept arguments" }
        val metabaseUrl = System.getenv("METABASE_URL") ?: "http://metabase:3000"
        val backendUrl = System.getenv("BACKEND_URL")
            ?: System.getenv("MIMIR_URL")
            ?: "http://mimir:9009/prometheus"
        val backendKind = System.getenv("BACKEND_KIND") ?: "mimir"
        require(backendKind == "mimir" || backendKind == "prometheus") { "Unsupported backend kind: $backendKind" }
        val api = MetabaseApi(metabaseUrl)
        api.waitUntilHealthy()

        val properties = api.getObject("/api/session/properties")
        val engines = properties["engines"]?.requireObject("Metabase engines")
            ?: error("Metabase session properties did not contain engines")
        check(engines["prometheus"] != null) { "Packaged prometheus driver was not registered" }

        val hasUserSetup = (properties["has-user-setup"] as? JsonValue.BooleanValue)?.value ?: false
        val session = if (hasUserSetup) {
            api.login()
        } else {
            val setupToken = properties["setup-token"]?.requireString("Metabase setup token")
                ?: error("Fresh Metabase instance did not expose a setup token")
            api.setup(setupToken)
        }
        val databaseId = api.findOrCreateDatabase(session, backendUrl, backendKind)

        api.assertCompletedQuery(session, instantQuery(databaseId), expectedMinimumRows = 1)
        api.assertCompletedQuery(session, parameterizedQuery(databaseId), expectedMinimumRows = 1)
        api.assertCompletedQuery(session, rangeQuery(databaseId, backendKind), expectedMinimumRows = 1, attempts = 20)
        val timestampFieldId = api.findTimestampFieldId(session, databaseId)
        api.assertCompletedQuery(
            session,
            dateRangeQuery(databaseId, timestampFieldId, backendKind),
            expectedMinimumRows = 1,
            attempts = 20,
        )
        api.assertCompletedQuery(
            session,
            labelValuesQuery(databaseId, backendKind),
            expectedMinimumRows = 1,
            attempts = 20,
        )
        api.assertCompletedQuery(session, emptyQuery(databaseId), expectedMinimumRows = 0)
        api.assertCsvExport(session, instantQuery(databaseId))
        val createDemo = System.getenv("CREATE_DEMO").equals("true", ignoreCase = true)
        if (createDemo) {
            require(backendKind == "mimir") { "The interactive demo is Mimir-only" }
            val dashboardId = api.ensureDemoDashboard(session, databaseId)
            println("Demo dashboard: $metabaseUrl/dashboard/$dashboardId")
        } else {
            AuthFixture().use { fixture ->
                api.assertManagedSecretAuthentication(session, fixture.baseUrl)
            }
        }
        println("Metabase packaged-driver smoke test passed for database $databaseId")
    }

    private fun instantQuery(databaseId: Int): Map<String, Any?> = datasetQuery(databaseId, "vector(1)")

    private fun parameterizedQuery(databaseId: Int): Map<String, Any?> = mapOf(
        "database" to databaseId,
        "type" to "native",
        "native" to mapOf(
            "query" to "vector({{value}})",
            "template-tags" to mapOf(
                "value" to mapOf(
                    "id" to "value-tag",
                    "name" to "value",
                    "display-name" to "Value",
                    "type" to "number",
                    "required" to true,
                ),
            ),
        ),
        "parameters" to listOf(
            mapOf(
                "type" to "number",
                "target" to listOf("variable", listOf("template-tag", "value")),
                "value" to "2",
            ),
        ),
    )

    private fun rangeQuery(databaseId: Int, backendKind: String): Map<String, Any?> = datasetQuery(
        databaseId,
        "# metabase-mimir mode=range step=5s\n${metricName(backendKind)}",
    )

    private fun labelValuesQuery(databaseId: Int, backendKind: String): Map<String, Any?> = datasetQuery(
        databaseId,
        "# metabase-mimir mode=label-values label=${labelName(backendKind)}\n${metricName(backendKind)}",
    )

    private fun dateRangeQuery(databaseId: Int, fieldId: Int, backendKind: String): Map<String, Any?> {
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        return mapOf(
            "database" to databaseId,
            "type" to "native",
            "native" to mapOf(
                "query" to "# metabase-mimir mode=range step=auto time={{when}}\n" +
                    "avg_over_time(${metricName(backendKind)}[${'$'}__interval])",
                "template-tags" to mapOf(
                    "when" to mapOf(
                        "id" to "when-tag",
                        "name" to "when",
                        "display-name" to "When",
                        "type" to "dimension",
                        "widget-type" to "date/all-options",
                        "dimension" to listOf("field", fieldId, null),
                    ),
                ),
            ),
            "parameters" to listOf(
                mapOf(
                    "type" to "date/range",
                    "target" to listOf("dimension", listOf("template-tag", "when")),
                    "value" to "$today~$today",
                ),
            ),
        )
    }

    private fun emptyQuery(databaseId: Int): Map<String, Any?> = datasetQuery(
        databaseId,
        "metabase_prometheus_driver_metric_that_does_not_exist",
    )

    private fun metricName(backendKind: String): String =
        if (backendKind == "mimir") "mimir_continuous_test_sine_wave_v2" else "up"

    private fun labelName(backendKind: String): String = if (backendKind == "mimir") "series_id" else "job"

    private fun datasetQuery(databaseId: Int, query: String): Map<String, Any?> = mapOf(
        "database" to databaseId,
        "type" to "native",
        "native" to mapOf("query" to query, "template-tags" to emptyMap<String, Any>()),
        "parameters" to emptyList<Any>(),
    )

    private class MetabaseApi(private val baseUrl: String) {
        private val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        fun waitUntilHealthy() {
            val deadline = Instant.now().plusSeconds(180)
            var lastResponse = "Metabase did not respond"
            while (Instant.now().isBefore(deadline)) {
                runCatching { request("GET", "/api/health") }.onSuccess { response ->
                    lastResponse = response
                    val status = runCatching {
                        Json.parse(response).requireObject("Metabase health")["status"]?.requireString("health status")
                    }.getOrNull()
                    if (status == "ok") return
                }
                Thread.sleep(500)
            }
            error("Metabase did not become healthy: ${lastResponse.take(500)}")
        }

        fun getObject(path: String, session: String? = null): JsonValue.ObjectValue =
            Json.parse(request("GET", path, session = session)).requireObject("Metabase response from $path")

        fun setup(token: String): String {
            val response = postObject(
                "/api/setup",
                mapOf(
                    "token" to token,
                    "prefs" to mapOf("site_name" to "Local Mimir Demo", "site_locale" to "en"),
                    "user" to mapOf(
                        "first_name" to "Local",
                        "last_name" to "Admin",
                        "email" to EMAIL,
                        "password" to PASSWORD,
                    ),
                ),
            )
            return response["id"]?.requireString("Setup session ID") ?: error("Setup did not return a session")
        }

        fun login(): String {
            val response = postObject("/api/session", mapOf("username" to EMAIL, "password" to PASSWORD))
            return response["id"]?.requireString("Login session ID") ?: error("Login did not return a session")
        }

        fun findOrCreateDatabase(session: String, backendUrl: String, backendKind: String): Int {
            val databaseName = if (backendKind == "mimir") "Local Mimir" else "Local Prometheus"
            return findOrCreateDatabase(
                session,
                databaseName,
                buildMap {
                    put("url", backendUrl)
                    put("auth-mode", "none")
                    if (backendKind == "mimir") put("tenant-id", "demo")
                },
            )
        }

        fun assertManagedSecretAuthentication(session: String, fixtureUrl: String) {
            val basicId = findOrCreateDatabase(
                session,
                "Secret Basic Authentication",
                mapOf(
                    "url" to "$fixtureUrl/basic",
                    "auth-mode" to "basic",
                    "username" to "metabase",
                    "password-value" to "managed-basic-secret",
                ),
            )
            assertCompletedQuery(session, instantQuery(basicId), expectedMinimumRows = 1)

            val bearerId = findOrCreateDatabase(
                session,
                "Secret Bearer Authentication",
                mapOf(
                    "url" to "$fixtureUrl/bearer",
                    "auth-mode" to "bearer",
                    "bearer-token-value" to "managed-bearer-secret",
                ),
            )
            assertCompletedQuery(session, instantQuery(bearerId), expectedMinimumRows = 1)
        }

        private fun findOrCreateDatabase(
            session: String,
            databaseName: String,
            details: Map<String, Any?>,
        ): Int {
            val existingResponse = Json.parse(request("GET", "/api/database", session = session))
            val existing = when (existingResponse) {
                is JsonValue.ArrayValue -> existingResponse.values
                is JsonValue.ObjectValue -> (existingResponse["data"] as? JsonValue.ArrayValue)?.values.orEmpty()
                else -> emptyList()
            }.mapNotNull { it as? JsonValue.ObjectValue }
                .firstOrNull { (it["name"] as? JsonValue.StringValue)?.value == databaseName }
            if (existing != null) return existing.requireInt("id")

            val created = postObject(
                "/api/database",
                mapOf(
                    "name" to databaseName,
                    "engine" to "prometheus",
                    "details" to details,
                ),
                session,
            )
            return created.requireInt("id")
        }

        fun assertCompletedQuery(
            session: String,
            query: Map<String, Any?>,
            expectedMinimumRows: Int,
            attempts: Int = 1,
        ) {
            var lastResponse: JsonValue.ObjectValue? = null
            repeat(attempts) { attempt ->
                val response = postObject("/api/dataset", query, session)
                lastResponse = response
                val status = (response["status"] as? JsonValue.StringValue)?.value
                val rows = response["data"]?.requireObject("Dataset response data")
                    ?.get("rows")?.requireArray("Dataset rows")?.values
                    ?: emptyList()
                if (status == "completed" && rows.size >= expectedMinimumRows) return
                if (attempt + 1 < attempts) Thread.sleep(1_000)
            }
            error("Dataset query did not complete with $expectedMinimumRows row(s): $lastResponse")
        }

        fun findTimestampFieldId(session: String, databaseId: Int): Int {
            val deadline = Instant.now().plusSeconds(60)
            var lastResponse = ""
            while (Instant.now().isBefore(deadline)) {
                lastResponse = request("GET", "/api/database/$databaseId/metadata", session = session)
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

        fun assertCsvExport(session: String, query: Map<String, Any?>) {
            val csv = request(
                "POST",
                "/api/dataset/csv",
                Json.stringify(
                    mapOf(
                        "query" to query,
                        "format_rows" to false,
                        "pivot_results" to false,
                        "visualization_settings" to emptyMap<String, Any>(),
                    ),
                ),
                session,
            )
            check(csv.contains("timestamp") && csv.contains("value")) { "CSV export did not contain result columns" }
        }

        fun ensureDemoDashboard(session: String, databaseId: Int): Int {
            val existingCards = request("GET", "/api/card?f=all", session = session)
            val cards = listOf(
                findOrCreateCard(
                    existingCards,
                    session,
                    "Mimir Connection",
                    "scalar",
                    datasetQuery(databaseId, "vector(1)"),
                    mapOf("scalar.field" to "value"),
                ),
                findOrCreateCard(
                    existingCards,
                    session,
                    "Mimir Continuous Test",
                    "line",
                    datasetQuery(
                        databaseId,
                        "# metabase-mimir mode=range step=5s\nmimir_continuous_test_sine_wave_v2",
                    ),
                    mapOf("graph.dimensions" to listOf("timestamp"), "graph.metrics" to listOf("value")),
                ),
                findOrCreateCard(
                    existingCards,
                    session,
                    "Mimir Series IDs",
                    "table",
                    datasetQuery(
                        databaseId,
                        "# metabase-mimir mode=label-values label=series_id\nmimir_continuous_test_sine_wave_v2",
                    ),
                    emptyMap(),
                ),
            )
            val dashboardId = findByName(
                request("GET", "/api/dashboard", session = session),
                "Mimir / Prometheus Demo",
            )?.requireInt("id") ?: postObject(
                "/api/dashboard",
                mapOf(
                    "name" to "Mimir / Prometheus Demo",
                    "description" to "Local dashboard backed by Grafana Mimir's Prometheus read API.",
                    "parameters" to emptyList<Any>(),
                ),
                session,
            ).requireInt("id")
            putObject(
                "/api/dashboard/$dashboardId",
                mapOf(
                    "dashcards" to cards.mapIndexed { index, cardId ->
                        mapOf(
                            "id" to -(index + 1),
                            "card_id" to cardId,
                            "row" to if (index == 0) 0 else 4,
                            "col" to if (index == 2) 12 else 0,
                            "size_x" to if (index == 0) 6 else 12,
                            "size_y" to if (index == 0) 4 else 8,
                            "parameter_mappings" to emptyList<Any>(),
                            "visualization_settings" to emptyMap<String, Any>(),
                        )
                    },
                    "tabs" to emptyList<Any>(),
                ),
                session,
            )
            return dashboardId
        }

        private fun findOrCreateCard(
            existingCards: String,
            session: String,
            name: String,
            display: String,
            query: Map<String, Any?>,
            visualizationSettings: Map<String, Any?>,
        ): Int {
            findByName(existingCards, name)?.let { return it.requireInt("id") }
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
            return response.requireInt("id")
        }

        private fun findByName(body: String, name: String): JsonValue.ObjectValue? {
            val response = Json.parse(body)
            val values = when (response) {
                is JsonValue.ArrayValue -> response.values
                is JsonValue.ObjectValue -> (response["data"] as? JsonValue.ArrayValue)?.values.orEmpty()
                else -> emptyList()
            }
            return values.mapNotNull { it as? JsonValue.ObjectValue }
                .firstOrNull { (it["name"] as? JsonValue.StringValue)?.value == name }
        }

        private fun postObject(
            path: String,
            body: Map<String, Any?>,
            session: String? = null,
        ): JsonValue.ObjectValue = Json.parse(request("POST", path, Json.stringify(body), session))
            .requireObject("Metabase response from $path")

        private fun putObject(
            path: String,
            body: Map<String, Any?>,
            session: String? = null,
        ): JsonValue.ObjectValue = Json.parse(request("PUT", path, Json.stringify(body), session))
            .requireObject("Metabase response from $path")

        private fun request(method: String, path: String, body: String? = null, session: String? = null): String {
            val builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(45))
                .header("Accept", "application/json")
            if (session != null) builder.header("X-Metabase-Session", session)
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody())
            } else {
                builder.header("Content-Type", "application/json")
                builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            }
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            check(response.statusCode() in 200..299) {
                "$method $path returned HTTP ${response.statusCode()}: ${response.body().take(2_000)}"
            }
            return response.body()
        }

        private fun JsonValue.ObjectValue.requireInt(name: String): Int {
            val number = this[name] as? JsonValue.NumberValue ?: error("Metabase response is missing numeric $name")
            return number.value.intValueExact()
        }
    }

    private class AuthFixture : AutoCloseable {
        private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
        private val server = HttpServer.create(InetSocketAddress("0.0.0.0", AUTH_FIXTURE_PORT), 0).apply {
            this.executor = this@AuthFixture.executor
            createContext("/basic/api/v1/query") { exchange ->
                exchange.respondToAuthorizedRequest(BASIC_AUTHORIZATION, buildInfo = false)
            }
            createContext("/basic/api/v1/status/buildinfo") { exchange ->
                exchange.respondToAuthorizedRequest(BASIC_AUTHORIZATION, buildInfo = true)
            }
            createContext("/bearer/api/v1/query") { exchange ->
                exchange.respondToAuthorizedRequest(BEARER_AUTHORIZATION, buildInfo = false)
            }
            createContext("/bearer/api/v1/status/buildinfo") { exchange ->
                exchange.respondToAuthorizedRequest(BEARER_AUTHORIZATION, buildInfo = true)
            }
            start()
        }

        val baseUrl: String = System.getenv("AUTH_FIXTURE_URL") ?: "http://toolchain:$AUTH_FIXTURE_PORT"

        private fun HttpExchange.respondToAuthorizedRequest(expectedAuthorization: String, buildInfo: Boolean) {
            val body = if (requestHeaders.getFirst("Authorization") == expectedAuthorization) {
                if (buildInfo) {
                    """{"status":"success","data":{"application":"Auth fixture","version":"1"}}"""
                } else {
                    """{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":[1,"1"]}]}}"""
                }
            } else {
                """{"status":"error","errorType":"unauthorized","error":"invalid authorization"}"""
            }
            val status = if (requestHeaders.getFirst("Authorization") == expectedAuthorization) 200 else 401
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            responseHeaders.set("Content-Type", "application/json")
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }

        override fun close() {
            server.stop(0)
            executor.close()
        }

        private companion object {
            const val AUTH_FIXTURE_PORT = 18_080
            val BASIC_AUTHORIZATION: String = "Basic " + Base64.getEncoder()
                .encodeToString("metabase:managed-basic-secret".toByteArray(StandardCharsets.UTF_8))
            const val BEARER_AUTHORIZATION = "Bearer managed-bearer-secret"
        }
    }
}
