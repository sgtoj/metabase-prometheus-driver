package io.cruxstack.metabase.prometheus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DirectiveTest {
    @Test
    fun `defaults to instant and preserves ordinary PromQL`() {
        val query = "# an ordinary comment\nvector(1)"
        assertEquals(ParsedNativeQuery(query, Directive()), Directive.parse(query))
    }

    @Test
    fun `parses and removes a range directive`() {
        val parsed = Directive.parse(
            """
            # metabase-mimir mode=range step=auto time={{time}}
            rate(requests_total[${'$'}__interval])
            """.trimIndent(),
        )
        assertEquals("rate(requests_total[${'$'}__interval])", parsed.promQl)
        assertEquals(Directive.Mode.RANGE, parsed.directive.mode)
        assertEquals(Directive.Step.Auto, parsed.directive.step)
        assertEquals("time", parsed.directive.timeTag)
    }

    @Test
    fun `requires label for label values mode`() {
        assertThrows(IllegalArgumentException::class.java) {
            Directive.parse("# metabase-mimir mode=label-values\n{__name__=\"up\"}")
        }
    }

    @Test
    fun `accepts Prometheus 3 UTF-8 label names without directive whitespace`() {
        val parsed = Directive.parse(
            "# metabase-mimir mode=label-values label=http.status/code\n{\"metric.name\"}",
        )
        assertEquals("http.status/code", parsed.directive.label)
    }

    @Test
    fun `rejects duplicate and unknown keys`() {
        assertThrows(IllegalArgumentException::class.java) {
            Directive.parse("# metabase-mimir mode=range mode=instant\nup")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Directive.parse("# metabase-mimir mode=range nope=yes\nup")
        }
    }
}
