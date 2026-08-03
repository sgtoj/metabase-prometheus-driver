package io.cruxstack.metabase.prometheus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class QueryCompilerTest {
    private val clock = Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC)
    private val config = DriverConfig.from(mapOf("url" to "http://mimir:9009/prometheus"))

    @Test
    fun `compiles an automatic range query`() {
        val query = QueryCompiler.compile(
            """
            # metabase-mimir mode=range step=auto time={{time}}
            rate(requests_total{service=~{{service}}}[${'$'}__interval]) offset ${'$'}__range
            """.trimIndent(),
            mapOf(
                "time" to ParameterValue.TimeRangeValue(
                    QueryTimeRange(
                        Instant.parse("2026-08-01T06:00:00Z"),
                        Instant.parse("2026-08-01T12:00:00Z"),
                    ),
                ),
                "service" to ParameterValue.Text("api.*"),
            ),
            config,
            clock,
        )
        assertEquals(Directive.Mode.RANGE, query.mode)
        assertEquals("rate(requests_total{service=~\"api.*\"}[20s]) offset 6h", query.promQl)
        assertEquals("20s", PromDuration.of(requireNotNull(query.step)).format())
        assertEquals(
            listOf(
                "query" to "rate(requests_total{service=~\"api.*\"}[20s]) offset 6h",
                "start" to "1785564000",
                "end" to "1785585600",
                "step" to "20s",
            ),
            query.formParameters(),
        )
    }

    @Test
    fun `defaults an instant query to the previous hour`() {
        val query = QueryCompiler.compile("vector(1) @ ${'$'}__end", emptyMap(), config, clock)
        assertEquals(Instant.parse("2026-08-01T11:00:00Z"), query.timeRange.start)
        assertEquals(Instant.parse("2026-08-01T12:00:00Z"), query.timeRange.end)
        assertEquals("vector(1) @ 1785585600", query.promQl)
    }

    @Test
    fun `normalizes metric names for label discovery`() {
        val query = QueryCompiler.compile(
            "# metabase-mimir mode=label-values label=job\nup",
            emptyMap(),
            config,
            clock,
        )
        assertEquals("{__name__=\"up\"}", query.promQl)
        assertEquals("job", query.label)

        val filtered = QueryCompiler.compile(
            "# metabase-mimir mode=label-values label=service\n" +
                "http_requests_total{environment=\"production\"}",
            emptyMap(),
            config,
            clock,
        )
        assertEquals("http_requests_total{environment=\"production\"}", filtered.promQl)
    }

    @Test
    fun `rejects excessive ranges and overly small explicit steps`() {
        val time = ParameterValue.TimeRangeValue(
            QueryTimeRange(Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z")),
        )
        assertThrows(IllegalArgumentException::class.java) {
            QueryCompiler.compile(
                "# metabase-mimir mode=range time={{time}}\nup",
                mapOf("time" to time),
                config,
                clock,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            QueryCompiler.compile(
                "# metabase-mimir mode=range step=1s\nup",
                emptyMap(),
                config,
                clock,
            )
        }
    }

    @Test
    fun `preserves fractional timestamps and enforces the exact inclusive point limit`() {
        val twoPointConfig = DriverConfig.from(
            mapOf("url" to "http://localhost", "maximum-data-points" to 2, "minimum-range-step" to "1s"),
        )
        val range = ParameterValue.TimeRangeValue(
            QueryTimeRange(Instant.ofEpochSecond(1, 250_000_000), Instant.ofEpochSecond(2, 250_000_000)),
        )
        val query = QueryCompiler.compile(
            "# metabase-mimir mode=range step=1s time={{time}}\nup @ ${'$'}__start",
            mapOf("time" to range),
            twoPointConfig,
            clock,
        )
        assertEquals("up @ 1.25", query.promQl)
        assertEquals("1.25", query.formParameters()[1].second)
        assertEquals("2.25", query.formParameters()[2].second)

        val onePointConfig = DriverConfig.from(
            mapOf("url" to "http://localhost", "maximum-data-points" to 1),
        )
        assertThrows(IllegalArgumentException::class.java) {
            QueryCompiler.compile(
                "# metabase-mimir mode=range time={{time}}\nup",
                mapOf("time" to range),
                onePointConfig,
                clock,
            )
        }
    }

    @Test
    fun `resolves extreme explicit steps and ranges without arithmetic overflow`() {
        val hugeStep = QueryCompiler.compile(
            "# metabase-mimir mode=range step=1000y\nup",
            emptyMap(),
            config,
            clock,
        )
        assertEquals("1000y", PromDuration.of(requireNotNull(hugeStep.step)).format())

        val wideRangeConfig = DriverConfig.from(
            mapOf("url" to "http://localhost", "maximum-query-range" to "2000y"),
        )
        val wideRange = QueryCompiler.compile(
            "# metabase-mimir mode=range step=auto time=@2000-01-01T00:00:00Z/3000-01-01T00:00:00Z\nup",
            emptyMap(),
            wideRangeConfig,
            clock,
        )
        val step = requireNotNull(wideRange.step)
        assertTrue(step > Duration.ZERO, "automatic step must stay positive")
        assertTrue(
            wideRange.timeRange.duration.dividedBy(step) <= DriverConfig.DEFAULT_MAXIMUM_DATA_POINTS - 1,
            "automatic step must respect the inclusive data point limit",
        )
    }

    @Test
    fun `maps an invalid native query to a validation driver error`() {
        val error = assertThrows(DriverQueryException::class.java) {
            PrometheusDriver.startQuery(
                mapOf("url" to "http://localhost:1"),
                "# metabase-mimir mode=range step=0s\nup",
            )
        }
        assertEquals(DriverQueryException.Category.VALIDATION, error.category)
    }
}
