package io.cruxstack.metabase.prometheus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ParameterSubstitutorTest {
    @Test
    fun `renders supported typed values`() {
        val query = "api_requests_per_second{route=~{{route}}, status=~{{statuses}}} > {{minimum}} == {{enabled}}"
        val result = ParameterSubstitutor.substitute(
            query,
            mapOf(
                "route" to ParameterValue.Text("items-.*\"primary"),
                "statuses" to ParameterValue.TextList(listOf("2..", "5|x")),
                "minimum" to ParameterValue.Number(BigDecimal("10.5000")),
                "enabled" to ParameterValue.BooleanValue(true),
            ),
        )
        assertEquals(
            "api_requests_per_second{route=~\"items-.*\\\"primary\", status=~\"(2\\\\.\\\\.|5\\\\|x)\"} > 10.5 == 1",
            result,
        )
    }

    @Test
    fun `renders multi-select values as escaped capturing alternatives`() {
        assertEquals(
            "api_requests_per_second{service=~\"(catalog|checkout)\"}",
            ParameterSubstitutor.substitute(
                "api_requests_per_second{service=~{{service}}}",
                mapOf("service" to ParameterValue.TextList(listOf("catalog", "checkout"))),
            ),
        )
        assertEquals(
            "api_requests_per_second{service=~\"(catalog\\\\.\\\\*)\"}",
            ParameterSubstitutor.substitute(
                "api_requests_per_second{service=~{{service}}}",
                mapOf("service" to ParameterValue.TextList(listOf("catalog.*"))),
            ),
        )
    }

    @Test
    fun `escapes quotes backslashes and regex metacharacters before PromQL quoting`() {
        assertEquals(
            "api_requests_per_second{service=~\"(catalog\\\"\\\\)\\\\|\\\\.\\\\*|checkout\\\\\\\\blue)\"}",
            ParameterSubstitutor.substitute(
                "api_requests_per_second{service=~{{service}}}",
                mapOf("service" to ParameterValue.TextList(listOf("catalog\")|.*", "checkout\\blue"))),
            ),
        )
    }

    @Test
    fun `includes optional blocks only when every value is present`() {
        val query = "api_requests_per_second{status=\"200\" [[, service=~{{service}}, status_class=~{{status_class}}]]}"
        assertEquals(
            "api_requests_per_second{status=\"200\" }",
            ParameterSubstitutor.substitute(
                query,
                mapOf("service" to ParameterValue.Text("catalog"), "status_class" to ParameterValue.Missing),
            ),
        )
        assertEquals(
            "api_requests_per_second{status=\"200\" , service=~\"catalog\", status_class=~\"2xx\"}",
            ParameterSubstitutor.substitute(
                query,
                mapOf("service" to ParameterValue.Text("catalog"), "status_class" to ParameterValue.Text("2xx")),
            ),
        )
    }

    @Test
    fun `does not substitute tags in strings or comments`() {
        val query = "label_replace(api_requests_per_second, \"{{service}}\", \"x\", \"route\", \"service\") # {{service}}\napi_requests_per_second{service={{service}}}"
        assertEquals(
            "label_replace(api_requests_per_second, \"{{service}}\", \"x\", \"route\", \"service\") # {{service}}\napi_requests_per_second{service=\"catalog\"}",
            ParameterSubstitutor.substitute(query, mapOf("service" to ParameterValue.Text("catalog"))),
        )
    }

    @Test
    fun `rejects missing non-optional and control-character values`() {
        assertThrows(IllegalArgumentException::class.java) {
            ParameterSubstitutor.substitute("api_requests_per_second{service={{service}}}", emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ParameterSubstitutor.substitute(
                "api_requests_per_second{service={{service}}}",
                mapOf("service" to ParameterValue.Text("catalog\u0000injected")),
            )
        }
    }
}
