package io.cruxstack.metabase.prometheus

data class ParsedNativeQuery(
    val promQl: String,
    val directive: Directive,
    val hasDirective: Boolean = false,
)

data class Directive(
    val mode: Mode = Mode.INSTANT,
    val step: Step? = null,
    val timeTag: String? = null,
    val fixedTimeRange: QueryTimeRange? = null,
    val label: String? = null,
) {
    enum class Mode(val wireValue: String) {
        INSTANT("instant"),
        RANGE("range"),
        LABEL_VALUES("label-values"),
    }

    sealed interface Step {
        data object Auto : Step
        data class Explicit(val duration: PromDuration) : Step
    }

    init {
        require(mode == Mode.RANGE || step == null) { "step is valid only for range mode" }
        require(mode == Mode.LABEL_VALUES || label == null) { "label is valid only for label-values mode" }
        require(mode != Mode.LABEL_VALUES || !label.isNullOrBlank()) { "label is required for label-values mode" }
        require(timeTag == null || fixedTimeRange == null) { "time cannot be both a tag and a fixed range" }
    }

    fun render(promQl: String): String = buildString {
        append(PREFIX).append(" mode=").append(mode.wireValue)
        when (val configuredStep = step) {
            Step.Auto -> append(" step=auto")
            is Step.Explicit -> append(" step=").append(configuredStep.duration.format())
            null -> Unit
        }
        if (timeTag != null) append(" time={{").append(timeTag).append("}}")
        if (fixedTimeRange != null) {
            append(" time=@")
                .append(fixedTimeRange.start)
                .append('/')
                .append(fixedTimeRange.end)
        }
        if (label != null) append(" label=").append(label)
        append('\n').append(promQl)
    }

    companion object {
        private const val PREFIX = "# metabase-mimir"
        private val labelName = Regex("[^\\s=]+")
        private val tag = Regex("\\{\\{([a-zA-Z0-9_-]+)}}")

        fun parse(query: String): ParsedNativeQuery {
            val lines = query.lines()
            val directiveLines = lines.withIndex().filter { it.value.trim().startsWith(PREFIX) }
            require(directiveLines.size <= 1) { "Only one # metabase-mimir directive is allowed" }

            val firstNonEmpty = lines.indexOfFirst { it.isNotBlank() }
            val directiveLine = directiveLines.singleOrNull()
            require(directiveLine == null || directiveLine.index == firstNonEmpty) {
                "The # metabase-mimir directive must be the first non-empty line"
            }
            if (directiveLine == null) {
                return ParsedNativeQuery(query, Directive(), false)
            }

            val content = directiveLine.value.trim().removePrefix(PREFIX).trim()
            val values = linkedMapOf<String, String>()
            if (content.isNotEmpty()) {
                for (part in content.split(Regex("\\s+"))) {
                    val pieces = part.split('=', limit = 2)
                    require(pieces.size == 2 && pieces[0].isNotEmpty() && pieces[1].isNotEmpty()) {
                        "Invalid directive entry: $part"
                    }
                    require(pieces[0] in setOf("mode", "step", "time", "label")) {
                        "Unknown directive key: ${pieces[0]}"
                    }
                    require(values.put(pieces[0], pieces[1]) == null) {
                        "Duplicate directive key: ${pieces[0]}"
                    }
                }
            }

            val mode = values["mode"]?.let { raw ->
                Mode.entries.singleOrNull { it.wireValue == raw }
                    ?: throw IllegalArgumentException("Invalid query mode: $raw")
            } ?: Mode.INSTANT
            val step = values["step"]?.let {
                if (it == "auto") Step.Auto else Step.Explicit(PromDuration.parse(it))
            }
            var fixedTimeRange: QueryTimeRange? = null
            val timeTag = values["time"]?.let { raw ->
                val tagMatch = tag.matchEntire(raw)
                if (tagMatch != null) {
                    tagMatch.groupValues[1]
                } else {
                    fixedTimeRange = parseFixedTimeRange(raw)
                    null
                }
            }
            val label = values["label"]?.also {
                require(labelName.matches(it)) { "Invalid Prometheus label name: $it" }
            }

            val promQl = lines.filterIndexed { index, _ -> index != directiveLine.index }.joinToString("\n")
            require(promQl.isNotBlank() || mode == Mode.LABEL_VALUES) { "PromQL query cannot be empty" }
            return ParsedNativeQuery(promQl, Directive(mode, step, timeTag, fixedTimeRange, label), true)
        }

        private fun parseFixedTimeRange(raw: String): QueryTimeRange {
            require(raw.startsWith('@')) { "time must be one Metabase template tag" }
            val parts = raw.removePrefix("@").split('/', limit = 2)
            require(parts.size == 2) { "Invalid resolved time range" }
            return try {
                QueryTimeRange(java.time.Instant.parse(parts[0]), java.time.Instant.parse(parts[1]))
            } catch (exception: java.time.format.DateTimeParseException) {
                throw IllegalArgumentException("Invalid resolved time range", exception)
            }
        }
    }
}
