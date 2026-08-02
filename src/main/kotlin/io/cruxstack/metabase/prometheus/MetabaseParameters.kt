package io.cruxstack.metabase.prometheus

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

object MetabaseParameters {
    fun substitute(
        nativeQuery: String,
        rawTemplateTags: Any?,
        rawParameters: Any?,
        timezoneId: String,
    ): String {
        val parsed = Directive.parse(nativeQuery)
        val tags = parseTags(rawTemplateTags)
        val rawParameterMaps = asCollection(rawParameters).mapNotNull { it as? Map<*, *> }
        val values = tags.associate { tag ->
            val parameter = rawParameterMaps.firstOrNull { targetIdentifier(value(it, "target")) in tag.identifiers }
            val rawValue = parameter?.let { value(it, "value") } ?: tag.defaultValue
            val parameterType = parameter?.let { keyName(value(it, "type")) }
            val converted = convertValue(tag, parameterType, rawValue, timezoneId)
            if (tag.required && !converted.hasValue()) {
                throw IllegalArgumentException("A value is required for parameter {{${tag.name}}}")
            }
            tag.name to converted
        }
        val substitutedPromQl = ParameterSubstitutor.substitute(parsed.promQl, values)
        if (!parsed.hasDirective) return substitutedPromQl

        val resolvedRange = parsed.directive.timeTag?.let { timeTag ->
            when (val timeValue = values[timeTag]) {
                is ParameterValue.TimeRangeValue -> timeValue.value
                null, ParameterValue.Missing -> null
                else -> throw IllegalArgumentException("Parameter {{$timeTag}} must be a date or date range")
            }
        } ?: parsed.directive.fixedTimeRange
        return parsed.directive.copy(timeTag = null, fixedTimeRange = resolvedRange).render(substitutedPromQl)
    }

    private fun parseTags(rawTemplateTags: Any?): List<Tag> = when (rawTemplateTags) {
        is Map<*, *> -> rawTemplateTags.entries.mapNotNull { (fallbackName, rawTag) ->
            (rawTag as? Map<*, *>)?.let { parseTag(it, fallbackName?.toString()) }
        }
        else -> asCollection(rawTemplateTags).mapNotNull { (it as? Map<*, *>)?.let { tag -> parseTag(tag, null) } }
    }

    private fun parseTag(rawTag: Map<*, *>, fallbackName: String?): Tag {
        val name = value(rawTag, "name")?.toString()?.takeIf { it.isNotBlank() }
            ?: fallbackName?.removePrefix(":")?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Template tag is missing its name")
        val id = value(rawTag, "id")?.toString()
        return Tag(
            name = name,
            identifiers = setOfNotNull(name, id),
            type = keyName(value(rawTag, "type")),
            required = value(rawTag, "required") == true,
            defaultValue = value(rawTag, "default"),
        )
    }

    private fun convertValue(tag: Tag, parameterType: String?, rawValue: Any?, timezoneId: String): ParameterValue {
        val isDateParameter = parameterType?.startsWith("date/") == true
        if (tag.type == "dimension" && parameterType != null && !isDateParameter) {
            throw IllegalArgumentException(
                "Field-filter parameter {{${tag.name}}} supports only date or date-range values",
            )
        }
        val values = asCollection(rawValue)
        val collectionValue = rawValue is Collection<*> || rawValue is Iterable<*>
        if (rawValue == null || values.isEmpty() && rawValue is Collection<*>) return ParameterValue.Missing
        val effective = if (values.size == 1) values.single() else rawValue
        if (isDateParameter || tag.type == "dimension") {
            return ParameterValue.TimeRangeValue(parseTimeRange(effective?.toString().orEmpty(), timezoneId))
        }
        if (parameterType?.contains("boolean") == true || effective is Boolean) {
            val boolean = when (effective) {
                is Boolean -> effective
                else -> effective?.toString()?.toBooleanStrictOrNull()
            } ?: throw IllegalArgumentException("Parameter {{${tag.name}}} must be a boolean")
            return ParameterValue.BooleanValue(boolean)
        }
        if (tag.type == "number" || parameterType?.contains("number") == true) {
            require(values.size <= 1) { "Number parameter {{${tag.name}}} cannot contain multiple values" }
            val number = effective?.toString()?.toBigDecimalOrNull()
                ?: throw IllegalArgumentException("Parameter {{${tag.name}}} must be a number")
            require(number.toDouble().isFinite()) { "Parameter {{${tag.name}}} must be finite" }
            return ParameterValue.Number(number)
        }
        return if (collectionValue) {
            ParameterValue.TextList(values.map { it?.toString().orEmpty() })
        } else {
            ParameterValue.Text(effective?.toString().orEmpty())
        }
    }

    private fun parseTimeRange(value: String, timezoneId: String): QueryTimeRange {
        require(value.isNotBlank()) { "Date parameter cannot be empty" }
        val zone = try {
            ZoneId.of(timezoneId)
        } catch (exception: Exception) {
            throw IllegalArgumentException("Invalid Metabase report timezone: $timezoneId", exception)
        }
        val parts = value.split('~', limit = 2)
        return if (parts.size == 2) {
            val start = parseRangeEndpoint(parts[0], zone, inclusiveDateEnd = false)
            val end = parseRangeEndpoint(parts[1], zone, inclusiveDateEnd = true)
            require(!end.isBefore(start)) { "Date range end must not precede its start" }
            QueryTimeRange(
                start,
                end,
            )
        } else {
            try {
                val date = parseDate(value)
                QueryTimeRange(
                    date.atStartOfDay(zone).toInstant(),
                    date.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1),
                )
            } catch (_: IllegalArgumentException) {
                val instant = parseInstant(value, zone)
                QueryTimeRange(instant, instant)
            }
        }
    }

    private fun parseRangeEndpoint(value: String, zone: ZoneId, inclusiveDateEnd: Boolean): Instant {
        val date = try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
        if (date == null) return parseInstant(value, zone)
        return if (inclusiveDateEnd) {
            date.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1)
        } else {
            date.atStartOfDay(zone).toInstant()
        }
    }

    private fun parseDate(value: String): LocalDate = try {
        LocalDate.parse(value)
    } catch (exception: DateTimeParseException) {
        throw IllegalArgumentException("Unsupported Metabase date value: $value", exception)
    }

    private fun parseInstant(value: String, zone: ZoneId): Instant {
        return runCatching { Instant.parse(value) }.getOrElse {
            runCatching { OffsetDateTime.parse(value).toInstant() }.getOrElse {
                runCatching { LocalDateTime.parse(value).atZone(zone).toInstant() }
                    .getOrElse { throw IllegalArgumentException("Unsupported Metabase date value: $value", it) }
            }
        }
    }

    private fun targetIdentifier(value: Any?): String? {
        if (value !is Collection<*>) return null
        val items = value.toList()
        if (items.size >= 2 && keyName(items[0]) == "template-tag") {
            val identifier = items[1]
            return if (identifier is Map<*, *>) value(identifier, "id")?.toString() else identifier?.toString()
        }
        return items.asSequence().mapNotNull(::targetIdentifier).firstOrNull()
    }

    private fun asCollection(value: Any?): List<Any?> = when (value) {
        null -> emptyList()
        is Collection<*> -> value.toList()
        is Iterable<*> -> value.toList()
        else -> listOf(value)
    }

    private fun value(map: Map<*, *>, name: String): Any? =
        map.entries.firstOrNull { keyName(it.key) == name }?.value

    private fun keyName(value: Any?): String? = value?.toString()?.removePrefix(":")

    private data class Tag(
        val name: String,
        val identifiers: Set<String>,
        val type: String?,
        val required: Boolean,
        val defaultValue: Any?,
    )
}
