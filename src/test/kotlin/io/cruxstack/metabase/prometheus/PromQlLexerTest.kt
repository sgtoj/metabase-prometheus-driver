package io.cruxstack.metabase.prometheus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PromQlLexerTest {
    @Test
    fun `replaces tokens only in executable PromQL`() {
        val query = """
            sum_over_time(api_requests_per_second[${'$'}__interval]) offset ${'$'}__range
            api_requests_per_second @ ${'$'}__start
            api_errors_per_second @ ${'$'}__end
            # keep ${'$'}__range ${'$'}__interval ${'$'}__start ${'$'}__end
            label_replace(api_requests_per_second, "${'$'}__start", "${'$'}__end", "service", "route")
            api_requests_per_second{route=`${'$'}__interval`}
        """.trimIndent()
        val actual = PromQlLexer.replaceReservedTokens(
            query,
            mapOf(
                "${'$'}__interval" to "1m",
                "${'$'}__range" to "6h",
                "${'$'}__start" to "start-seconds",
                "${'$'}__end" to "end-seconds",
            ),
        )
        assertEquals(
            """
            sum_over_time(api_requests_per_second[1m]) offset 6h
            api_requests_per_second @ start-seconds
            api_errors_per_second @ end-seconds
            # keep ${'$'}__range ${'$'}__interval ${'$'}__start ${'$'}__end
            label_replace(api_requests_per_second, "${'$'}__start", "${'$'}__end", "service", "route")
            api_requests_per_second{route=`${'$'}__interval`}
            """.trimIndent(),
            actual,
        )
    }

    @Test
    fun `fails when an executable token has no value`() {
        assertThrows(IllegalArgumentException::class.java) {
            PromQlLexer.replaceReservedTokens("api_requests_per_second offset ${'$'}__start", emptyMap())
        }
    }
}
