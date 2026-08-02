package io.cruxstack.metabase.prometheus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class MetabaseParametersTest {
    @Test
    fun `resolves legacy tags multi-values and an inclusive date range`() {
        val query = """
            # metabase-mimir mode=range step=auto time={{when}}
            rate(requests_total{service=~{{service}}}[${'$'}__interval])
        """.trimIndent()
        val tags = mapOf(
            "service" to mapOf("name" to "service", "type" to "text"),
            "when" to mapOf("name" to "when", "type" to "dimension"),
        )
        val parameters = listOf(
            mapOf(
                "type" to "string/=",
                "target" to listOf("variable", listOf("template-tag", "service")),
                "value" to listOf("api", "worker"),
            ),
            mapOf(
                "type" to "date/range",
                "target" to listOf("dimension", listOf("template-tag", "when")),
                "value" to "2026-07-01~2026-08-01",
            ),
        )
        val substituted = MetabaseParameters.substitute(query, tags, parameters, "UTC")
        val parsed = Directive.parse(substituted)

        assertEquals(
            "rate(requests_total{service=~\"(api|worker)\"}[${'$'}__interval])",
            parsed.promQl,
        )
        assertNull(parsed.directive.timeTag)
        assertEquals(Instant.parse("2026-07-01T00:00:00Z"), parsed.directive.fixedTimeRange?.start)
        assertEquals(Instant.parse("2026-08-01T23:59:59.999999999Z"), parsed.directive.fixedTimeRange?.end)
    }

    @Test
    fun `resolves ordered stage tags by ID and applies defaults`() {
        val tags = listOf(
            mapOf("id" to "tag-service", "name" to "service", "type" to "text", "default" to "default-api"),
            mapOf("id" to "tag-count", "name" to "count", "type" to "number"),
        )
        val parameters = listOf(
            mapOf(
                "type" to "number",
                "target" to listOf("variable", listOf("template-tag", mapOf("id" to "tag-count"))),
                "value" to "2.50",
            ),
        )
        assertEquals(
            "metric{service=\"default-api\"} > 2.5",
            MetabaseParameters.substitute(
                "metric{service={{service}}} > {{count}}",
                tags,
                parameters,
                "UTC",
            ),
        )
    }

    @Test
    fun `escapes a one-item multi-select as a literal regex alternative`() {
        val tags = mapOf("service" to mapOf("name" to "service", "type" to "text"))
        val parameters = listOf(
            mapOf(
                "type" to "string/=",
                "target" to listOf("variable", listOf("template-tag", "service")),
                "value" to listOf("api.*"),
            ),
        )
        assertEquals(
            "metric{service=~\"(api\\\\.\\\\*)\"}",
            MetabaseParameters.substitute("metric{service=~{{service}}}", tags, parameters, "UTC"),
        )
    }

    @Test
    fun `preserves exact timestamp endpoints in a date range`() {
        val tags = mapOf("when" to mapOf("name" to "when", "type" to "dimension"))
        val parameters = listOf(
            mapOf(
                "type" to "date/range",
                "target" to listOf("dimension", listOf("template-tag", "when")),
                "value" to "2026-01-01T00:00:00Z~2026-01-01T06:00:00Z",
            ),
        )

        val parsed = Directive.parse(
            MetabaseParameters.substitute(
                "# metabase-mimir mode=range step=60s time={{when}}\nrequests_total",
                tags,
                parameters,
                "UTC",
            ),
        )

        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), parsed.directive.fixedTimeRange?.start)
        assertEquals(Instant.parse("2026-01-01T06:00:00Z"), parsed.directive.fixedTimeRange?.end)
    }

    @Test
    fun `rejects non-date field-filter parameter types explicitly`() {
        val tags = mapOf("when" to mapOf("name" to "when", "type" to "dimension"))

        listOf("string/=", "number/=", "boolean/=").forEach { type ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                MetabaseParameters.substitute(
                    "# metabase-mimir mode=range time={{when}}\nrequests_total",
                    tags,
                    listOf(
                        mapOf(
                            "type" to type,
                            "target" to listOf("dimension", listOf("template-tag", "when")),
                            "value" to "catalog",
                        ),
                    ),
                    "UTC",
                )
            }
            assertEquals(
                "Field-filter parameter {{when}} supports only date or date-range values",
                error.message,
            )
        }
    }

    @Test
    fun `removes an optional block for a missing parameter`() {
        val tags = mapOf("service" to mapOf("name" to "service", "type" to "text"))
        assertEquals(
            "metric{}",
            MetabaseParameters.substitute("metric{[[service=~{{service}}]]}", tags, emptyList<Any>(), "UTC"),
        )
    }

    @Test
    fun `rejects missing required and unsupported date values`() {
        val required = mapOf(
            "service" to mapOf("name" to "service", "type" to "text", "required" to true),
        )
        assertThrows(IllegalArgumentException::class.java) {
            MetabaseParameters.substitute("metric{service={{service}}}", required, emptyList<Any>(), "UTC")
        }
        val date = mapOf("when" to mapOf("name" to "when", "type" to "dimension"))
        val parameter = listOf(
            mapOf(
                "type" to "date/range",
                "target" to listOf("dimension", listOf("template-tag", "when")),
                "value" to "past30days",
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            MetabaseParameters.substitute(
                "# metabase-mimir mode=range time={{when}}\nup",
                date,
                parameter,
                "UTC",
            )
        }
    }

    @Test
    fun `uses the Metabase report timezone across a daylight-saving boundary`() {
        val tags = mapOf("when" to mapOf("name" to "when", "type" to "dimension"))
        val parameter = listOf(
            mapOf(
                "type" to "date/single",
                "target" to listOf("dimension", listOf("template-tag", "when")),
                "value" to "2026-03-08",
            ),
        )
        val parsed = Directive.parse(
            MetabaseParameters.substitute(
                "# metabase-mimir mode=range time={{when}}\nup",
                tags,
                parameter,
                "America/New_York",
            ),
        )
        assertEquals(Instant.parse("2026-03-08T05:00:00Z"), parsed.directive.fixedTimeRange?.start)
        assertEquals(Instant.parse("2026-03-09T03:59:59.999999999Z"), parsed.directive.fixedTimeRange?.end)
    }
}
