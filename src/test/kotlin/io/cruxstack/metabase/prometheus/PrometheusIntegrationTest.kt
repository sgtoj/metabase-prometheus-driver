package io.cruxstack.metabase.prometheus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.Duration
import java.time.Instant

@EnabledIfEnvironmentVariable(named = "PROMETHEUS_URL", matches = ".+")
class PrometheusIntegrationTest {
    private val url = requireNotNull(System.getenv("PROMETHEUS_URL"))
    private val config = DriverConfig.from(
        mapOf(
            "url" to url,
            "query-timeout" to "10s",
            "minimum-range-step" to "1s",
        ),
    )

    @Test
    fun `executes the supported read contract against Prometheus`() {
        waitUntilReady()
        PrometheusClient(config, "prometheus-integration").use { client ->
            assertEquals(
                1.0,
                client.execute(QueryCompiler.compile("vector(1)", emptyMap(), config)).rows.single().last(),
            )

            assertTrue(waitForRows(client) {
                val now = Instant.now()
                QueryCompiler.compile(
                    "# metabase-mimir mode=range step=1s time={{time}}\nup",
                    mapOf(
                        "time" to ParameterValue.TimeRangeValue(QueryTimeRange(now.minusSeconds(15), now)),
                    ),
                    config,
                )
            }.rows.isNotEmpty())

            assertTrue(waitForRows(client) {
                QueryCompiler.compile(
                    "# metabase-mimir mode=label-values label=job\nup{job=\"prometheus\"}",
                    emptyMap(),
                    config,
                )
            }.rows.any { it.single() == "prometheus" })
            assertNotNull(client.buildInfo())
        }
    }

    private fun waitUntilReady() {
        val deadline = Instant.now().plus(Duration.ofSeconds(30))
        var lastFailure: Throwable? = null
        while (Instant.now().isBefore(deadline)) {
            try {
                if (PrometheusDriver.canConnect(mapOf("url" to url, "query-timeout" to "3s"))) return
            } catch (exception: Throwable) {
                lastFailure = exception
                Thread.sleep(250)
            }
        }
        throw AssertionError("Prometheus did not become queryable", lastFailure)
    }

    private fun waitForRows(client: PrometheusClient, query: () -> CompiledQuery): NormalizedResult {
        val deadline = Instant.now().plus(Duration.ofSeconds(30))
        var result = client.execute(query())
        while (result.rows.isEmpty() && Instant.now().isBefore(deadline)) {
            Thread.sleep(250)
            result = client.execute(query())
        }
        assertTrue(result.rows.isNotEmpty(), "Prometheus scrape data was not queryable before the deadline")
        return result
    }
}
