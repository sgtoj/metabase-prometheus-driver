package io.cruxstack.metabase.prometheus

object PromQlLexer {
    private val reservedTokens = listOf(
        "${'$'}__interval",
        "${'$'}__range",
        "${'$'}__start",
        "${'$'}__end",
    )

    fun replaceReservedTokens(query: String, replacements: Map<String, String>): String {
        val result = StringBuilder(query.length)
        var index = 0
        var state = State.CODE
        var escaped = false

        while (index < query.length) {
            val character = query[index]
            when (state) {
                State.CODE -> {
                    when (character) {
                        '#' -> {
                            state = State.COMMENT
                            result.append(character)
                            index++
                        }
                        '\'', '"', '`' -> {
                            state = when (character) {
                                '\'' -> State.SINGLE_QUOTE
                                '"' -> State.DOUBLE_QUOTE
                                else -> State.RAW_QUOTE
                            }
                            escaped = false
                            result.append(character)
                            index++
                        }
                        '$' -> {
                            val token = reservedTokens.firstOrNull { query.startsWith(it, index) }
                            if (token == null) {
                                result.append(character)
                                index++
                            } else {
                                val replacement = replacements[token]
                                    ?: throw IllegalArgumentException("Reserved token $token cannot be resolved")
                                result.append(replacement)
                                index += token.length
                            }
                        }
                        else -> {
                            result.append(character)
                            index++
                        }
                    }
                }
                State.COMMENT -> {
                    result.append(character)
                    index++
                    if (character == '\n') state = State.CODE
                }
                State.SINGLE_QUOTE, State.DOUBLE_QUOTE -> {
                    result.append(character)
                    index++
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
                State.RAW_QUOTE -> {
                    result.append(character)
                    index++
                    if (character == '`') state = State.CODE
                }
            }
        }
        return result.toString()
    }

    private enum class State {
        CODE,
        COMMENT,
        SINGLE_QUOTE,
        DOUBLE_QUOTE,
        RAW_QUOTE,
    }
}
