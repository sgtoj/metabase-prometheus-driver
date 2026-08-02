package io.cruxstack.metabase.prometheus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class JsonTest {
    @Test
    fun `parses all JSON value types`() {
        assertEquals(
            JsonValue.ObjectValue(
                linkedMapOf(
                    "text" to JsonValue.StringValue("line\n\u263a"),
                    "number" to JsonValue.NumberValue(BigDecimal("-12.50e2")),
                    "yes" to JsonValue.BooleanValue(true),
                    "none" to JsonValue.NullValue,
                    "array" to JsonValue.ArrayValue(listOf(JsonValue.NumberValue(BigDecimal.ONE))),
                ),
            ),
            Json.parse("""{"text":"line\n\u263a","number":-12.50e2,"yes":true,"none":null,"array":[1]}"""),
        )
    }

    @Test
    fun `rejects malformed and duplicate content`() {
        listOf(
            "",
            "{",
            "[1,]",
            "{\"a\":1,\"a\":2}",
            "01",
            "\"unclosed",
            "true false",
        ).forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { Json.parse(input) }
        }
    }

    @Test
    fun `serializes deterministic JSON without runtime dependencies`() {
        assertEquals(
            "{\"message\":\"line\\n\\\"quoted\\\"\",\"values\":[1,true,null]}",
            Json.stringify(
                linkedMapOf(
                    "message" to "line\n\"quoted\"",
                    "values" to listOf(1, true, null),
                ),
            ),
        )
    }
}
