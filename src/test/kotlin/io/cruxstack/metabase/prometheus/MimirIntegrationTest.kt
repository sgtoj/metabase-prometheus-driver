package io.cruxstack.metabase.prometheus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.Clock
import java.time.Duration
import java.time.Instant

@EnabledIfEnvironmentVariable(named = "MIMIR_URL", matches = ".+")
class MimirIntegrationTest {
    private val url = requireNotNull(System.getenv("MIMIR_URL"))
    private val config = DriverConfig.from(
        mapOf(
            "url" to url,
            "tenant-id" to "demo",
            "query-timeout" to "10s",
            "minimum-range-step" to "1s",
        ),
    )

    @Test
    fun `executes instant range and label discovery against Mimir`() {
        waitUntilReady()
        PrometheusClient(config, "mimir-integration").use { client ->
            val instant = QueryCompiler.compile("vector(1)", emptyMap(), config)
            assertEquals(1.0, client.execute(instant).rows.single().last())

            val rangeResult = waitForRows(client) {
                val now = Instant.now()
                QueryCompiler.compile(
                    "# metabase-mimir mode=range step=5s time={{time}}\nmimir_continuous_test_sine_wave_v2",
                    mapOf(
                        "time" to ParameterValue.TimeRangeValue(QueryTimeRange(now.minusSeconds(120), now)),
                    ),
                    config,
                    Clock.systemUTC(),
                )
            }
            assertTrue(rangeResult.columns.any { it.name == "series_id" })

            assertTrue(waitForRows(client) {
                QueryCompiler.compile(
                    "# metabase-mimir mode=label-values label=series_id\nmimir_continuous_test_sine_wave_v2",
                    emptyMap(),
                    config,
                )
            }.rows.isNotEmpty())
            assertNotNull(client.buildInfo())
        }
    }

    @Test
    fun `requires a tenant when Mimir multitenancy is enabled`() {
        waitUntilReady()
        val noTenant = DriverConfig.from(mapOf("url" to url, "query-timeout" to "10s"))
        val error = assertThrows(DriverQueryException::class.java) {
            PrometheusClient(noTenant).execute(QueryCompiler.compile("vector(1)", emptyMap(), noTenant))
        }
        assertTrue(error.category == DriverQueryException.Category.HTTP || error.category == DriverQueryException.Category.BACKEND)
    }

    private fun waitUntilReady() {
        val deadline = Instant.now().plus(Duration.ofSeconds(45))
        var lastFailure: Throwable? = null
        while (Instant.now().isBefore(deadline)) {
            try {
                if (PrometheusDriver.canConnect(
                        mapOf("url" to url, "tenant-id" to "demo", "query-timeout" to "3s"),
                    )
                ) {
                    return
                }
            } catch (exception: Throwable) {
                lastFailure = exception
                Thread.sleep(500)
            }
        }
        throw AssertionError("Mimir did not become queryable", lastFailure)
    }

    private fun waitForRows(client: PrometheusClient, query: () -> CompiledQuery): NormalizedResult {
        val deadline = Instant.now().plus(Duration.ofSeconds(45))
        var result = client.execute(query())
        while (result.rows.isEmpty() && Instant.now().isBefore(deadline)) {
            Thread.sleep(500)
            result = client.execute(query())
        }
        assertTrue(result.rows.isNotEmpty(), "Mimir seed data was not queryable before the deadline")
        return result
    }
}
