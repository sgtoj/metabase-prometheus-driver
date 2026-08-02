package io.cruxstack.metabase.prometheus

import java.math.BigDecimal

sealed interface JsonValue {
    data class ObjectValue(val values: Map<String, JsonValue>) : JsonValue {
        operator fun get(key: String): JsonValue? = values[key]
    }

    data class ArrayValue(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val value: BigDecimal) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

object Json {
    fun parse(
        input: String,
        maximumNodes: Int = Int.MAX_VALUE,
        checkpoint: () -> Unit = {},
    ): JsonValue {
        require(maximumNodes > 0) { "maximumNodes must be positive" }
        return Parser(input, maximumNodes, checkpoint).parse()
    }

    fun stringify(value: Any?): String = buildString { appendJson(value) }

    private fun StringBuilder.appendJson(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> appendJsonString(value)
            is Boolean -> append(value)
            is Number -> {
                require(value.toDouble().isFinite()) { "JSON numbers must be finite" }
                append(value)
            }
            is Map<*, *> -> {
                append('{')
                value.entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) append(',')
                    appendJsonString(key?.toString() ?: throw IllegalArgumentException("JSON object key cannot be null"))
                    append(':')
                    appendJson(item)
                }
                append('}')
            }
            is Iterable<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendJson(item)
                }
                append(']')
            }
            is Array<*> -> appendJson(value.asIterable())
            else -> throw IllegalArgumentException("Unsupported JSON value type: ${value.javaClass.name}")
        }
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
                }
            }
        }
        append('"')
    }

    private class Parser(
        private val input: String,
        private val maximumNodes: Int,
        private val checkpoint: () -> Unit,
    ) {
        private var index = 0
        private var nodes = 0

        fun parse(): JsonValue {
            skipWhitespace()
            val value = parseValue(0)
            skipWhitespace()
            require(index == input.length) { error("Unexpected trailing JSON content") }
            return value
        }

        private fun parseValue(depth: Int): JsonValue {
            checkpoint()
            require(depth <= MAX_DEPTH) { error("JSON nesting exceeds $MAX_DEPTH levels") }
            require(index < input.length) { error("Unexpected end of JSON") }
            if (++nodes > maximumNodes) throw JsonParsingLimitException("JSON value count exceeds $maximumNodes")
            return when (input[index]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> JsonValue.StringValue(parseString())
                't' -> parseLiteral("true", JsonValue.BooleanValue(true))
                'f' -> parseLiteral("false", JsonValue.BooleanValue(false))
                'n' -> parseLiteral("null", JsonValue.NullValue)
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException(error("Unexpected JSON token"))
            }
        }

        private fun parseObject(depth: Int): JsonValue.ObjectValue {
            index++
            skipWhitespace()
            val values = linkedMapOf<String, JsonValue>()
            if (consume('}')) return JsonValue.ObjectValue(values)
            while (true) {
                require(peek() == '"') { error("JSON object key must be a string") }
                val key = parseString()
                skipWhitespace()
                require(consume(':')) { error("Expected ':' after JSON object key") }
                skipWhitespace()
                val value = parseValue(depth)
                require(values.put(key, value) == null) { error("Duplicate JSON object key: $key") }
                skipWhitespace()
                if (consume('}')) break
                require(consume(',')) { error("Expected ',' or '}' in JSON object") }
                skipWhitespace()
            }
            return JsonValue.ObjectValue(values)
        }

        private fun parseArray(depth: Int): JsonValue.ArrayValue {
            index++
            skipWhitespace()
            val values = mutableListOf<JsonValue>()
            if (consume(']')) return JsonValue.ArrayValue(values)
            while (true) {
                values += parseValue(depth)
                skipWhitespace()
                if (consume(']')) break
                require(consume(',')) { error("Expected ',' or ']' in JSON array") }
                skipWhitespace()
            }
            return JsonValue.ArrayValue(values)
        }

        private fun parseString(): String {
            require(consume('"')) { error("Expected JSON string") }
            val result = StringBuilder()
            while (index < input.length) {
                if (index % CHECKPOINT_INTERVAL == 0) checkpoint()
                val character = input[index++]
                when (character) {
                    '"' -> return result.toString()
                    '\\' -> {
                        require(index < input.length) { error("Unclosed JSON escape") }
                        when (val escaped = input[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000c')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> result.append(parseUnicodeEscape())
                            else -> throw IllegalArgumentException(error("Invalid JSON escape: \\$escaped"))
                        }
                    }
                    else -> {
                        require(character.code >= 0x20) { error("Unescaped control character in JSON string") }
                        result.append(character)
                    }
                }
            }
            throw IllegalArgumentException(error("Unclosed JSON string"))
        }

        private fun parseUnicodeEscape(): Char {
            require(index + 4 <= input.length) { error("Incomplete JSON Unicode escape") }
            val digits = input.substring(index, index + 4)
            val codePoint = digits.toIntOrNull(16)
                ?: throw IllegalArgumentException(error("Invalid JSON Unicode escape"))
            index += 4
            return codePoint.toChar()
        }

        private fun parseNumber(): JsonValue.NumberValue {
            val start = index
            if (peek() == '-') index++
            require(index < input.length) { error("Incomplete JSON number") }
            if (peek() == '0') {
                index++
            } else {
                require(peek() in '1'..'9') { error("Invalid JSON number") }
                while (peek() in '0'..'9') index++
            }
            if (peek() == '.') {
                index++
                require(peek() in '0'..'9') { error("Invalid JSON number fraction") }
                while (peek() in '0'..'9') index++
            }
            if (peek() == 'e' || peek() == 'E') {
                index++
                if (peek() == '+' || peek() == '-') index++
                require(peek() in '0'..'9') { error("Invalid JSON number exponent") }
                while (peek() in '0'..'9') index++
            }
            val value = input.substring(start, index).toBigDecimalOrNull()
                ?: throw IllegalArgumentException(error("Invalid JSON number"))
            return JsonValue.NumberValue(value)
        }

        private fun <T : JsonValue> parseLiteral(text: String, value: T): T {
            require(input.startsWith(text, index)) { error("Invalid JSON literal") }
            index += text.length
            return value
        }

        private fun skipWhitespace() {
            while (peek() == ' ' || peek() == '\n' || peek() == '\r' || peek() == '\t') index++
        }

        private fun consume(expected: Char): Boolean {
            if (peek() != expected) return false
            index++
            return true
        }

        private fun peek(): Char? = input.getOrNull(index)

        private fun error(message: String): String = "$message at byte offset $index"
    }

    private const val MAX_DEPTH = 100
    private const val CHECKPOINT_INTERVAL = 1_024
}

internal class JsonParsingLimitException(message: String) : IllegalArgumentException(message)

internal fun JsonValue.requireObject(context: String): JsonValue.ObjectValue =
    this as? JsonValue.ObjectValue ?: throw IllegalArgumentException("$context must be a JSON object")

internal fun JsonValue.requireArray(context: String): JsonValue.ArrayValue =
    this as? JsonValue.ArrayValue ?: throw IllegalArgumentException("$context must be a JSON array")

internal fun JsonValue.requireString(context: String): String =
    (this as? JsonValue.StringValue)?.value ?: throw IllegalArgumentException("$context must be a JSON string")
