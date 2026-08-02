package io.cruxstack.metabase.prometheus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class ResultConverterTest {
    private val config = DriverConfig.from(mapOf("url" to "http://localhost:9009/prometheus"))

    @Test
    fun `converts matrix results with deterministic dynamic labels`() {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val body = """
            {
              "status":"success",
              "data":{"resultType":"matrix","result":[
                {"metric":{"status_class":"2xx","__name__":"api_requests_per_second","service":"catalog","route":"/items"},
                 "values":[[${start.epochSecond}.125,"42"],[${start.plusSeconds(60).epochSecond},"NaN"]]},
                {"metric":{"__name__":"api_requests_per_second","service":"checkout","route":"/checkout"},
                 "values":[[${start.epochSecond},"7.5"]]}
              ]},
              "warnings":["partial response"]
            }
        """.trimIndent()
        val result = ResultConverter.convert(body, Directive.Mode.RANGE, config)

        assertEquals(
            listOf("timestamp", "series", "metric", "route", "service", "status_class", "value"),
            result.columns.map { it.name },
        )
        assertEquals(Instant.parse("2026-01-01T00:00:00.125Z"), result.rows[0][0])
        assertEquals(
            "api_requests_per_second{route=\"/items\",service=\"catalog\",status_class=\"2xx\"}",
            result.rows[0][1],
        )
        assertEquals(
            listOf("api_requests_per_second", "/items", "catalog", "2xx", 42.0),
            result.rows[0].drop(2),
        )
        assertNull(result.rows[1].last())
        assertNull(result.rows[2][5])
        assertEquals(listOf("partial response", "1 non-finite Prometheus sample value(s) were returned as null"), result.warnings)
        assertEquals(2, result.seriesCount)
    }

    @Test
    fun `converts native histogram vector results`() {
        val timestamp = Instant.parse("2026-01-01T00:00:00.125Z")
        val result = ResultConverter.convert(
            """
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"__name__":"api_latency_milliseconds_bucket","service":"catalog"},
                   "histogram":[${timestamp.epochSecond}.${timestamp.nano.toString().padStart(9, '0')},{"count":"4","sum":"7.5","buckets":[
                     [1,"-1","-0.5","1"],[3,"-0.5","0.5","2"],[0,"0.5","1","1"]
                   ]}]}
                ]}}
            """.trimIndent(),
            Directive.Mode.INSTANT,
            config,
        )

        assertEquals(
            listOf(
                "timestamp",
                "series",
                "metric",
                "service",
                "value",
                "histogram_count",
                "histogram_sum",
                "histogram_buckets",
            ),
            result.columns.map { it.name },
        )
        assertEquals(
            listOf(
                timestamp,
                "api_latency_milliseconds_bucket{service=\"catalog\"}",
                "api_latency_milliseconds_bucket",
                "catalog",
                null,
                4.0,
                7.5,
                "[[1,\"-1\",\"-0.5\",\"1\"],[3,\"-0.5\",\"0.5\",\"2\"],[0,\"0.5\",\"1\",\"1\"]]",
            ),
            result.rows.single(),
        )
    }

    @Test
    fun `merges mixed matrix samples in timestamp order`() {
        val result = ResultConverter.convert(
            """
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"route":"/items"},
                   "values":[[3,"30"],[1,"10"]],
                   "histograms":[
                     [4,{"count":"4","sum":"8"}],
                     [2,{"count":"2","sum":"3","buckets":[[0,"0","1","2"]]}]
                   ]}
                ]}}
            """.trimIndent(),
            Directive.Mode.RANGE,
            config,
        )

        assertEquals(listOf(1L, 2L, 3L, 4L), result.rows.map { (it[0] as Instant).epochSecond })
        assertEquals(listOf(10.0, null, null, null), result.rows[0].drop(4))
        assertEquals(listOf(null, 2.0, 3.0, "[[0,\"0\",\"1\",\"2\"]]"), result.rows[1].drop(4))
        assertEquals(listOf(30.0, null, null, null), result.rows[2].drop(4))
        assertEquals(listOf(null, 4.0, 8.0, "[]"), result.rows[3].drop(4))
    }

    @Test
    fun `converts vector scalar string and label values`() {
        val vector = ResultConverter.convert(
            """{"status":"success","data":{"resultType":"vector","result":[{"metric":{"service":"catalog"},"value":[1,"2"]}]}}""",
            Directive.Mode.INSTANT,
            config,
        )
        assertEquals(listOf("timestamp", "series", "metric", "service", "value"), vector.columns.map { it.name })
        assertEquals(
            listOf(Instant.ofEpochSecond(1), "{service=\"catalog\"}", null, "catalog", 2.0),
            vector.rows.single(),
        )

        val scalar = ResultConverter.convert(
            """{"status":"success","data":{"resultType":"scalar","result":[2,"+Inf"]}}""",
            Directive.Mode.INSTANT,
            config,
        )
        assertNull(scalar.rows.single()[1])

        val string = ResultConverter.convert(
            """{"status":"success","data":{"resultType":"string","result":[3,"ready"]}}""",
            Directive.Mode.INSTANT,
            config,
        )
        assertEquals(listOf(Instant.ofEpochSecond(3), "ready"), string.rows.single())

        val labels = ResultConverter.convert(
            """{"status":"success","data":["catalog","checkout"]}""",
            Directive.Mode.LABEL_VALUES,
            config,
        )
        assertEquals(listOf(listOf("catalog"), listOf("checkout")), labels.rows)
    }

    @Test
    fun `returns every synthetic non-finite sample including stale NaN as null`() {
        val result = ResultConverter.convert(
            """{"status":"success","data":{"resultType":"matrix","result":[{"metric":{},"values":[[1,"NaN"],[2,"StaleNaN"],[3,"+Inf"],[4,"-Inf"]]}]}}""",
            Directive.Mode.RANGE,
            config,
        )

        assertEquals(listOf(null, null, null, null), result.rows.map { it.last() })
        assertEquals(
            listOf("4 non-finite Prometheus sample value(s) were returned as null"),
            result.warnings,
        )
    }

    @Test
    fun `returns useful standard metadata for empty series results`() {
        val result = ResultConverter.convert(
            """{"status":"success","data":{"resultType":"matrix","result":[]}}""",
            Directive.Mode.RANGE,
            config,
        )
        assertEquals(listOf("timestamp", "series", "metric", "value"), result.columns.map { it.name })
        assertEquals(emptyList<List<Any?>>(), result.rows)
    }

    @Test
    fun `disambiguates labels that conflict with standard columns`() {
        val result = ResultConverter.convert(
            """{"status":"success","data":{"resultType":"vector","result":[{"metric":{"value":"label"},"value":[1,"2"]}]}}""",
            Directive.Mode.INSTANT,
            config,
        )
        assertEquals(listOf("timestamp", "series", "metric", "label_value", "value"), result.columns.map { it.name })
    }

    @Test
    fun `quotes Prometheus 3 UTF-8 names in canonical series text`() {
        val result = ResultConverter.convert(
            """{"status":"success","data":{"resultType":"vector","result":[{"metric":{"__name__":"metric.name","label.name":"value"},"value":[1,"2"]}]}}""",
            Directive.Mode.INSTANT,
            config,
        )
        assertEquals("{\"metric.name\",\"label.name\"=\"value\"}", result.rows.single()[1])
    }

    @Test
    fun `enforces rows points and backend errors`() {
        val oneRow = DriverConfig.from(
            mapOf("url" to "http://localhost", "maximum-returned-rows" to 10, "maximum-data-points" to 1),
        )
        assertEquals(
            1,
            ResultConverter.convert(
                """{"status":"success","data":{"resultType":"matrix","result":[{"metric":{},"values":[[1,"1"]]}]}}""",
                Directive.Mode.RANGE,
                oneRow,
            ).rows.size,
        )
        assertThrows(DriverQueryException::class.java) {
            ResultConverter.convert(
                """{"status":"success","data":{"resultType":"matrix","result":[{"metric":{},"values":[[1,"1"],[2,"2"]]}]}}""",
                Directive.Mode.RANGE,
                oneRow,
            )
        }
        val error = assertThrows(DriverQueryException::class.java) {
            ResultConverter.convert(
                """{"status":"error","errorType":"bad_data","error":"invalid query"}""",
                Directive.Mode.INSTANT,
                config,
            )
        }
        assertEquals(DriverQueryException.Category.VALIDATION, error.category)
        assertEquals("Mimir query failed (bad_data): invalid query", error.message)
    }

    @Test
    fun `rejects malformed and unexpected result types`() {
        listOf(
            "not json",
            """{"status":"success","data":{"resultType":"unknown","result":[]}}""",
            """{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":["bad","1"]}]}}""",
            """{"status":"success","data":{"resultType":"scalar","result":[1,"garbage"]}}""",
            """{"status":"success","data":{"resultType":"scalar","result":[1e1000,"1"]}}""",
            """{"status":1,"data":{}}""",
        ).forEach { body ->
            val error = assertThrows(DriverQueryException::class.java) {
                ResultConverter.convert(body, Directive.Mode.INSTANT, config)
            }
            assertEquals(DriverQueryException.Category.MALFORMED_RESPONSE, error.category)
        }
    }

    @Test
    fun `rejects malformed native histograms`() {
        listOf(
            """[1,{"sum":"1"}]""",
            """[1,{"count":"1","sum":"bad"}]""",
            """[1,{"count":"1","sum":"1","buckets":[[0,"0","1"]]}]""",
            """[1,{"count":"1","sum":"1","buckets":[[4,"0","1","1"]]}]""",
        ).forEach { histogram ->
            val error = assertThrows(DriverQueryException::class.java) {
                ResultConverter.convert(
                    """{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"histogram":$histogram}]}}""",
                    Directive.Mode.INSTANT,
                    config,
                )
            }
            assertEquals(DriverQueryException.Category.MALFORMED_RESPONSE, error.category)
        }

        val bothKinds = assertThrows(DriverQueryException::class.java) {
            ResultConverter.convert(
                """{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":[1,"1"],"histogram":[1,{"count":"1","sum":"1"}]}]}}""",
                Directive.Mode.INSTANT,
                config,
            )
        }
        assertEquals(DriverQueryException.Category.MALFORMED_RESPONSE, bothKinds.category)
    }

    @Test
    fun `applies point and row limits across mixed and histogram samples`() {
        val onePoint = DriverConfig.from(
            mapOf("url" to "http://localhost", "maximum-returned-rows" to 10, "maximum-data-points" to 1),
        )
        val pointError = assertThrows(DriverQueryException::class.java) {
            ResultConverter.convert(
                """{"status":"success","data":{"resultType":"matrix","result":[{"metric":{},"values":[[1,"1"]],"histograms":[[2,{"count":"1","sum":"1"}]]}]}}""",
                Directive.Mode.RANGE,
                onePoint,
            )
        }
        assertEquals(DriverQueryException.Category.GUARDRAIL, pointError.category)

        val oneRow = DriverConfig.from(
            mapOf("url" to "http://localhost", "maximum-returned-rows" to 1, "maximum-data-points" to 2),
        )
        val rowError = assertThrows(DriverQueryException::class.java) {
            ResultConverter.convert(
                """{"status":"success","data":{"resultType":"matrix","result":[{"metric":{},"histograms":[[1,{"count":"1","sum":"1"}],[2,{"count":"1","sum":"1"}]]}]}}""",
                Directive.Mode.RANGE,
                oneRow,
            )
        }
        assertEquals(DriverQueryException.Category.GUARDRAIL, rowError.category)
    }

    @Test
    fun `bounds dynamic columns and total materialized cells`() {
        val tooManyLabels = (0..256).joinToString(",") { "\"label_$it\":\"value\"" }
        val labelError = assertThrows(DriverQueryException::class.java) {
            ResultConverter.convert(
                """{"status":"success","data":{"resultType":"vector","result":[{"metric":{$tooManyLabels},"value":[1,"1"]}]}}""",
                Directive.Mode.INSTANT,
                config,
            )
        }
        assertEquals(DriverQueryException.Category.GUARDRAIL, labelError.category)

        val labels = (0 until 250).joinToString(",") { "\"label_$it\":\"value\"" }
        val histograms = (0 until 8_000).joinToString(",") { "[$it,{\"count\":\"1\",\"sum\":\"1\"}]" }
        val largeConfig = DriverConfig.from(
            mapOf(
                "url" to "http://localhost",
                "maximum-returned-rows" to 10_000,
                "maximum-data-points" to 10_000,
            ),
        )
        val cellError = assertThrows(DriverQueryException::class.java) {
            ResultConverter.convert(
                """{"status":"success","data":{"resultType":"matrix","result":[{"metric":{$labels},"histograms":[$histograms]}]}}""",
                Directive.Mode.RANGE,
                largeConfig,
            )
        }
        assertEquals(DriverQueryException.Category.GUARDRAIL, cellError.category)
    }

    @Test
    fun `bounds JSON structure before conversion and observes cancellation`() {
        val oneRow = DriverConfig.from(mapOf("url" to "http://localhost", "maximum-returned-rows" to 1))
        val buckets = List(2_100) { "[0,\"0\",\"1\",\"1\"]" }.joinToString(",")
        val structuralError = assertThrows(DriverQueryException::class.java) {
            ResultConverter.convert(
                """{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"histogram":[1,{"count":"1","sum":"1","buckets":[$buckets]}]}]}}""",
                Directive.Mode.INSTANT,
                oneRow,
            )
        }
        assertEquals(DriverQueryException.Category.GUARDRAIL, structuralError.category)

        val cancellation = RequestCancellation().apply { cancel() }
        val canceled = assertThrows(DriverQueryException::class.java) {
            ResultConverter.convert(
                """{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"histogram":[1,{"count":"1","sum":"1"}]}]}}""",
                Directive.Mode.INSTANT,
                config,
                cancellation = cancellation,
            )
        }
        assertEquals(DriverQueryException.Category.CANCELED, canceled.category)
    }
}
