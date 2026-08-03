package io.cruxstack.metabase.prometheus

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.math.BigDecimal

data class QueryTimeRange(val start: Instant, val end: Instant) {
    init {
        require(!end.isBefore(start)) { "Query range end must not precede its start" }
    }

    val duration: Duration = Duration.between(start, end)
}

data class CompiledQuery(
    val mode: Directive.Mode,
    val promQl: String,
    val timeRange: QueryTimeRange,
    val step: Duration?,
    val label: String?,
) {
    fun formParameters(timeout: Duration? = null): List<Pair<String, String>> {
        val parameters = when (mode) {
            Directive.Mode.INSTANT -> listOf(
                "query" to promQl,
                "time" to timeRange.end.prometheusTimestamp(),
            )
            Directive.Mode.RANGE -> listOf(
                "query" to promQl,
                "start" to timeRange.start.prometheusTimestamp(),
                "end" to timeRange.end.prometheusTimestamp(),
                "step" to PromDuration.of(requireNotNull(step)).format(),
            )
            Directive.Mode.LABEL_VALUES -> listOf(
                "match[]" to promQl,
                "start" to timeRange.start.prometheusTimestamp(),
                "end" to timeRange.end.prometheusTimestamp(),
            )
        }
        return if (timeout != null && mode != Directive.Mode.LABEL_VALUES) {
            parameters + ("timeout" to PromDuration.of(timeout).format())
        } else {
            parameters
        }
    }
}

object QueryCompiler {
    private val metricName = Regex("[a-zA-Z_:][a-zA-Z0-9_:]*")

    fun compile(
        nativeQuery: String,
        parameters: Map<String, ParameterValue>,
        config: DriverConfig,
        clock: Clock = Clock.systemUTC(),
    ): CompiledQuery {
        val parsed = Directive.parse(nativeQuery)
        val range = resolveTimeRange(
            parsed.directive.timeTag,
            parsed.directive.fixedTimeRange,
            parameters,
            clock,
        )
        require(range.duration <= config.maximumQueryRange) {
            "Query range ${PromDuration.of(range.duration).format()} exceeds maximum " +
                PromDuration.of(config.maximumQueryRange).format()
        }

        val substituted = ParameterSubstitutor.substitute(parsed.promQl, parameters)
        val step = if (parsed.directive.mode == Directive.Mode.RANGE) {
            resolveStep(parsed.directive.step, range.duration, config)
        } else {
            null
        }
        val selector = if (parsed.directive.mode == Directive.Mode.LABEL_VALUES) {
            normalizeSelector(substituted.trim())
        } else {
            substituted
        }
        val replacements = buildMap {
            put("${'$'}__range", PromDuration.of(range.duration).format())
            put("${'$'}__start", range.start.prometheusTimestamp())
            put("${'$'}__end", range.end.prometheusTimestamp())
            if (step != null) put("${'$'}__interval", PromDuration.of(step).format())
        }
        return CompiledQuery(
            mode = parsed.directive.mode,
            promQl = PromQlLexer.replaceReservedTokens(selector, replacements),
            timeRange = range,
            step = step,
            label = parsed.directive.label,
        )
    }

    private fun resolveTimeRange(
        timeTag: String?,
        fixedTimeRange: QueryTimeRange?,
        parameters: Map<String, ParameterValue>,
        clock: Clock,
    ): QueryTimeRange {
        if (fixedTimeRange != null) return fixedTimeRange
        if (timeTag != null) {
            val value = parameters[timeTag]
            if (value is ParameterValue.TimeRangeValue) return value.value
            if (value != null && value.hasValue()) {
                throw IllegalArgumentException("Parameter {{$timeTag}} must be a date or date range")
            }
        }
        val now = clock.instant()
        return QueryTimeRange(now.minus(Duration.ofHours(1)), now)
    }

    private fun resolveStep(step: Directive.Step?, range: Duration, config: DriverConfig): Duration {
        val selectedStep = step ?: Directive.Step.Auto
        val resolved = when (selectedStep) {
            Directive.Step.Auto -> {
                if (!range.isZero) {
                    require(config.maximumDataPoints >= 2) {
                        "A non-empty range query requires a maximum data point limit of at least 2"
                    }
                }
                val intervals = (config.maximumDataPoints - 1).coerceAtLeast(1)
                val calculatedSeconds = secondsRoundedUp(range.dividedBy(intervals.toLong())).coerceAtLeast(1)
                maxOf(config.minimumRangeStep, Duration.ofSeconds(calculatedSeconds))
            }
            is Directive.Step.Explicit -> selectedStep.duration.duration
        }
        require(!resolved.isZero && !resolved.isNegative) { "Range step must be greater than zero" }
        // Duration division is exact for every representable duration, and comparing intervals
        // rather than inclusive points keeps an extreme range or step from overflowing instead of
        // being reported as an exceeded limit.
        val intervalCount = range.dividedBy(resolved)
        require(intervalCount <= config.maximumDataPoints - 1) {
            "Range step would return more than ${config.maximumDataPoints} data points per series"
        }
        return resolved
    }

    private fun secondsRoundedUp(duration: Duration): Long =
        duration.seconds + if (duration.nano > 0) 1 else 0

    private fun normalizeSelector(value: String): String {
        require(value.isNotEmpty()) { "label-values query must contain a metric name or series selector" }
        if (metricName.matches(value)) {
            return "{__name__=${ParameterSubstitutor.quotePromQlString(value)}}"
        }
        val openingBrace = value.indexOf('{')
        val hasMetricPrefix = openingBrace > 0 && metricName.matches(value.substring(0, openingBrace))
        require((value.startsWith('{') || hasMetricPrefix) && value.endsWith('}')) {
            "label-values query must contain a metric name or series selector"
        }
        return value
    }
}

internal fun Instant.prometheusTimestamp(): String = BigDecimal.valueOf(epochSecond)
    .add(BigDecimal.valueOf(nano.toLong(), 9))
    .stripTrailingZeros()
    .toPlainString()
