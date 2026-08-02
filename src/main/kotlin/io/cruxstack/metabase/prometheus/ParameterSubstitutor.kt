package io.cruxstack.metabase.prometheus

object ParameterSubstitutor {
    private val tagName = Regex("[a-zA-Z0-9_-]+")
    private val regexMetaCharacters = setOf('\\', '.', '^', '$', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}', '-')

    fun substitute(query: String, parameters: Map<String, ParameterValue>): String {
        val withoutOptionalBlocks = resolveOptionalBlocks(query, parameters)
        return replaceTags(withoutOptionalBlocks, parameters)
    }

    private fun resolveOptionalBlocks(query: String, parameters: Map<String, ParameterValue>): String {
        val result = StringBuilder(query.length)
        var cursor = 0
        while (true) {
            val start = findCodeSequence(query, "[[", cursor) ?: break
            result.append(query, cursor, start)
            val end = findCodeSequence(query, "]]", start + 2)
                ?: throw IllegalArgumentException("Unclosed optional block")
            require(findCodeSequence(query, "[[", start + 2)?.let { it > end } != false) {
                "Nested optional blocks are not supported"
            }
            val content = query.substring(start + 2, end)
            val names = findTags(content)
            require(names.isNotEmpty()) { "Optional block must contain at least one parameter" }
            if (names.all { parameters[it]?.hasValue() == true }) {
                result.append(content)
            }
            cursor = end + 2
        }
        result.append(query, cursor, query.length)
        return result.toString()
    }

    private fun replaceTags(query: String, parameters: Map<String, ParameterValue>): String {
        val result = StringBuilder(query.length)
        var cursor = 0
        while (true) {
            val start = findCodeSequence(query, "{{", cursor) ?: break
            result.append(query, cursor, start)
            val end = query.indexOf("}}", start + 2)
            require(end >= 0) { "Unclosed Metabase template tag" }
            val name = query.substring(start + 2, end).trim()
            require(tagName.matches(name)) { "Invalid Metabase template tag: $name" }
            val value = parameters[name]
                ?: throw IllegalArgumentException("No value was supplied for parameter {{$name}}")
            require(value.hasValue()) { "No value was supplied for parameter {{$name}}" }
            result.append(render(value, name))
            cursor = end + 2
        }
        result.append(query, cursor, query.length)
        return result.toString()
    }

    private fun findTags(content: String): Set<String> {
        val names = linkedSetOf<String>()
        var cursor = 0
        while (true) {
            val start = findCodeSequence(content, "{{", cursor) ?: break
            val end = content.indexOf("}}", start + 2)
            require(end >= 0) { "Unclosed Metabase template tag in optional block" }
            val name = content.substring(start + 2, end).trim()
            require(tagName.matches(name)) { "Invalid Metabase template tag: $name" }
            names += name
            cursor = end + 2
        }
        return names
    }

    private fun render(value: ParameterValue, name: String): String = when (value) {
        is ParameterValue.Text -> quotePromQlString(value.value)
        is ParameterValue.TextList -> {
            require(value.values.isNotEmpty()) { "Parameter {{$name}} has no values" }
            val regex = value.values.joinToString(separator = "|", prefix = "(", postfix = ")") {
                escapeRegexLiteral(it)
            }
            quotePromQlString(regex)
        }
        is ParameterValue.Number -> value.value.stripTrailingZeros().toPlainString()
        is ParameterValue.BooleanValue -> if (value.value) "1" else "0"
        is ParameterValue.TimeRangeValue -> throw IllegalArgumentException(
            "Date parameter {{$name}} can be used only by the directive's time key",
        )
        ParameterValue.Missing -> throw IllegalArgumentException("No value was supplied for parameter {{$name}}")
    }

    internal fun quotePromQlString(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    require(character.code >= 0x20 && character != 0x7f.toChar()) {
                        "Text parameters cannot contain control characters"
                    }
                    append(character)
                }
            }
        }
        append('"')
    }

    private fun escapeRegexLiteral(value: String): String = buildString(value.length) {
        value.forEach { character ->
            if (character in regexMetaCharacters) append('\\')
            append(character)
        }
    }

    private fun findCodeSequence(input: String, target: String, fromIndex: Int): Int? {
        var index = fromIndex
        var state = State.CODE
        var escaped = false
        while (index < input.length) {
            val character = input[index]
            when (state) {
                State.CODE -> when (character) {
                    '#' -> state = State.COMMENT
                    '\'' -> state = State.SINGLE_QUOTE
                    '"' -> state = State.DOUBLE_QUOTE
                    '`' -> state = State.RAW_QUOTE
                    else -> if (input.startsWith(target, index)) return index
                }
                State.COMMENT -> if (character == '\n') state = State.CODE
                State.SINGLE_QUOTE, State.DOUBLE_QUOTE -> {
                    if (escaped) {
                        escaped = false
                    } else if (character == '\\') {
                        escaped = true
                    } else if (
                        (state == State.SINGLE_QUOTE && character == '\'') ||
                        (state == State.DOUBLE_QUOTE && character == '"')
                    ) {
                        state = State.CODE
                    }
                }
                State.RAW_QUOTE -> if (character == '`') state = State.CODE
            }
            index++
        }
        return null
    }

    private enum class State {
        CODE,
        COMMENT,
        SINGLE_QUOTE,
        DOUBLE_QUOTE,
        RAW_QUOTE,
    }
}
