package io.cruxstack.metabase.prometheus

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.Instant

enum class ColumnType {
    TEMPORAL,
    TEXT,
    FLOAT,
}

data class QueryColumn(val name: String, val type: ColumnType)

data class NormalizedResult(
    val columns: List<QueryColumn>,
    val rows: List<List<Any?>>,
    val warnings: List<String>,
    val seriesCount: Int,
    val responseBytes: Int,
)

object ResultConverter {
    private val standardNames = setOf(
        "timestamp",
        "series",
        "metric",
        "value",
        "histogram_count",
        "histogram_sum",
        "histogram_buckets",
    )

    fun convert(
        body: String,
        mode: Directive.Mode,
        config: DriverConfig,
        responseBytes: Int = body.toByteArray(Charsets.UTF_8).size,
        cancellation: RequestCancellation = RequestCancellation(),
    ): NormalizedResult = try {
        cancellation.start(config.queryTimeout)
        convertResponse(body, mode, config, responseBytes, cancellation)
    } catch (exception: DriverQueryException) {
        throw exception
    } catch (exception: IllegalArgumentException) {
        throw DriverQueryException(
            DriverQueryException.Category.MALFORMED_RESPONSE,
            "Mimir returned a malformed response: ${safeText(exception.message ?: "invalid response")}",
            exception,
        )
    }

    private fun convertResponse(
        body: String,
        mode: Directive.Mode,
        config: DriverConfig,
        responseBytes: Int,
        cancellation: RequestCancellation,
    ): NormalizedResult {
        val root = try {
            Json.parse(body, maximumJsonNodes(config), cancellation::checkpoint)
                .requireObject("Prometheus response")
        } catch (exception: DriverQueryException) {
            throw exception
        } catch (exception: JsonParsingLimitException) {
            throw DriverQueryException(
                DriverQueryException.Category.GUARDRAIL,
                "Mimir response exceeds the JSON structural limit",
                exception,
            )
        } catch (exception: IllegalArgumentException) {
            throw DriverQueryException(
                DriverQueryException.Category.MALFORMED_RESPONSE,
                "Mimir returned malformed JSON: ${safeText(exception.message ?: "invalid JSON")}",
                exception,
            )
        }
        val status = root["status"]?.requireString("Prometheus response status")
            ?: malformed("Prometheus response is missing status")
        if (status == "error") throw backendError(root)
        if (status != "success") malformed("Unexpected Prometheus response status: ${safeText(status)}")

        val warnings = mutableListOf<String>()
        warnings += stringArray(root["warnings"], "warnings", cancellation)
        warnings += stringArray(root["infos"], "infos", cancellation)
        val data = root["data"] ?: malformed("Prometheus response is missing data")
        return if (mode == Directive.Mode.LABEL_VALUES) {
            convertLabelValues(data, warnings, config, responseBytes, cancellation)
        } else {
            convertQueryData(data, warnings, config, responseBytes, cancellation)
        }
    }

    private fun convertLabelValues(
        data: JsonValue,
        warnings: MutableList<String>,
        config: DriverConfig,
        responseBytes: Int,
        cancellation: RequestCancellation,
    ): NormalizedResult {
        val values = data.requireArray("Label-values data").values
        enforceRows(values.size, config)
        val rows = values.mapIndexed { index, value ->
            cancellation.checkpoint()
            listOf(value.requireString("Label value at index $index"))
        }
        return NormalizedResult(
            columns = listOf(QueryColumn("value", ColumnType.TEXT)),
            rows = rows,
            warnings = warnings,
            seriesCount = 0,
            responseBytes = responseBytes,
        )
    }

    private fun convertQueryData(
        data: JsonValue,
        warnings: MutableList<String>,
        config: DriverConfig,
        responseBytes: Int,
        cancellation: RequestCancellation,
    ): NormalizedResult {
        val objectValue = data.requireObject("Prometheus response data")
        val resultType = objectValue["resultType"]?.requireString("Prometheus result type")
            ?: malformed("Prometheus response is missing resultType")
        val result = objectValue["result"] ?: malformed("Prometheus response is missing result")
        return when (resultType) {
            "matrix" -> convertSeries(result, matrix = true, warnings, config, responseBytes, cancellation)
            "vector" -> convertSeries(result, matrix = false, warnings, config, responseBytes, cancellation)
            "scalar" -> convertSingle(result, text = false, warnings, config, responseBytes, cancellation)
            "string" -> convertSingle(result, text = true, warnings, config, responseBytes, cancellation)
            else -> malformed("Unsupported Prometheus result type: ${safeText(resultType)}")
        }
    }

    private fun convertSeries(
        result: JsonValue,
        matrix: Boolean,
        warnings: MutableList<String>,
        config: DriverConfig,
        responseBytes: Int,
        cancellation: RequestCancellation,
    ): NormalizedResult {
        val rawSeries = result.requireArray("Prometheus series result").values
        if (rawSeries.size > config.maximumReturnedRows) excessiveRows(config)
        var totalRows = 0L
        var hasHistograms = false
        val allLabelNames = sortedSetOf<String>()
        rawSeries.forEachIndexed { index, value ->
            cancellation.checkpoint()
            val item = seriesObject(value, index)
            val metric = item["metric"]?.requireObject("Metric labels for series $index")
                ?: malformed("Series $index is missing metric labels")
            val samples = sampleValues(item, index, matrix)
            val sampleCount = samples.values.size.toLong() + samples.histograms.size
            if (sampleCount > config.maximumDataPoints) {
                throw DriverQueryException(
                    DriverQueryException.Category.GUARDRAIL,
                    "Mimir returned more than ${config.maximumDataPoints} data points for one series",
                )
            }
            totalRows += sampleCount
            if (totalRows > config.maximumReturnedRows) excessiveRows(config)
            hasHistograms = hasHistograms || samples.histograms.isNotEmpty()
            allLabelNames += metric.values.keys.filterNot { it == "__name__" }
            if (allLabelNames.size > MAX_DYNAMIC_LABEL_COLUMNS) {
                throw DriverQueryException(
                    DriverQueryException.Category.GUARDRAIL,
                    "Mimir result exceeds the $MAX_DYNAMIC_LABEL_COLUMNS dynamic-label column limit",
                )
            }
        }
        val labelColumns = disambiguateLabelColumns(allLabelNames)
        val standardColumnCount = STANDARD_COLUMN_COUNT + if (hasHistograms) HISTOGRAM_COLUMN_COUNT else 0
        val resultCells = totalRows * (standardColumnCount + labelColumns.size)
        if (resultCells > MAX_RESULT_CELLS) {
            throw DriverQueryException(
                DriverQueryException.Category.GUARDRAIL,
                "Mimir result exceeds the $MAX_RESULT_CELLS cell materialization limit",
            )
        }
        val columns = buildList {
            add(QueryColumn("timestamp", ColumnType.TEMPORAL))
            add(QueryColumn("series", ColumnType.TEXT))
            add(QueryColumn("metric", ColumnType.TEXT))
            labelColumns.forEach { add(QueryColumn(it.outputName, ColumnType.TEXT)) }
            add(QueryColumn("value", ColumnType.FLOAT))
            if (hasHistograms) {
                add(QueryColumn("histogram_count", ColumnType.FLOAT))
                add(QueryColumn("histogram_sum", ColumnType.FLOAT))
                add(QueryColumn("histogram_buckets", ColumnType.TEXT))
            }
        }

        var nonFiniteSamples = 0
        var nonFiniteHistogramValues = 0
        val rows = ArrayList<List<Any?>>(totalRows.toInt())
        rawSeries.forEachIndexed { index, value ->
            cancellation.checkpoint()
            val item = seriesObject(value, index)
            val labels = requireNotNull(item["metric"]).requireObject("Metric labels for series $index")
                .values.mapValues { (name, labelValue) ->
                    labelValue.requireString("Metric label $name for series $index")
            }
            val metric = labels["__name__"]
            val canonical = canonicalSeries(metric, labels)
            val samples = sampleValues(item, index, matrix)
            val parsedSamples = ArrayList<ParsedSeriesSample>(samples.values.size + samples.histograms.size)
            samples.values.forEachIndexed { sampleIndex, rawSample ->
                cancellation.checkpoint()
                val context = if (matrix) {
                    "Matrix sample $sampleIndex for series $index"
                } else {
                    "Vector sample for series $index"
                }
                val sample = parseSamplePair(rawSample, context)
                val numericValue = parseSample(sample.value, context)
                if (numericValue == null) nonFiniteSamples++
                parsedSamples += ParsedSeriesSample(
                    timestamp = sample.timestamp,
                    value = numericValue,
                    histogram = null,
                    sourceOrder = sampleIndex,
                )
            }
            samples.histograms.forEachIndexed { sampleIndex, rawSample ->
                cancellation.checkpoint()
                val context = if (matrix) {
                    "Matrix histogram $sampleIndex for series $index"
                } else {
                    "Vector histogram for series $index"
                }
                val sample = parseHistogramPair(rawSample, context, cancellation)
                nonFiniteHistogramValues += sample.nonFiniteValues
                parsedSamples += ParsedSeriesSample(
                    timestamp = sample.timestamp,
                    value = null,
                    histogram = sample.histogram,
                    sourceOrder = samples.values.size + sampleIndex,
                )
            }
            parsedSamples.sortWith(compareBy<ParsedSeriesSample> { it.timestamp }.thenBy { it.sourceOrder })
            for (sample in parsedSamples) {
                cancellation.checkpoint()
                if (rows.size >= config.maximumReturnedRows) excessiveRows(config)
                rows.add(buildList<Any?> {
                    add(sample.timestamp)
                    add(canonical)
                    add(metric)
                    labelColumns.forEach { add(labels[it.labelName]) }
                    add(sample.value)
                    if (hasHistograms) {
                        add(sample.histogram?.count)
                        add(sample.histogram?.sum)
                        add(sample.histogram?.buckets)
                    }
                })
            }
        }
        if (nonFiniteSamples > 0) {
            warnings += "$nonFiniteSamples non-finite Prometheus sample value(s) were returned as null"
        }
        if (nonFiniteHistogramValues > 0) {
            warnings += "$nonFiniteHistogramValues non-finite Prometheus histogram count/sum value(s) were returned as null"
        }
        return NormalizedResult(columns, rows, warnings, rawSeries.size, responseBytes)
    }

    private fun convertSingle(
        result: JsonValue,
        text: Boolean,
        warnings: MutableList<String>,
        config: DriverConfig,
        responseBytes: Int,
        cancellation: RequestCancellation,
    ): NormalizedResult {
        cancellation.checkpoint()
        val sample = parseSamplePair(result, "Prometheus ${if (text) "string" else "scalar"} result")
        enforceRows(1, config)
        val value = if (text) {
            sample.value
        } else {
            parseSample(sample.value, "Prometheus scalar result").also {
                if (it == null) warnings += "A non-finite Prometheus scalar value was returned as null"
            }
        }
        return NormalizedResult(
            columns = listOf(
                QueryColumn("timestamp", ColumnType.TEMPORAL),
                QueryColumn("value", if (text) ColumnType.TEXT else ColumnType.FLOAT),
            ),
            rows = listOf(listOf(sample.timestamp, value)),
            warnings = warnings,
            seriesCount = 0,
            responseBytes = responseBytes,
        )
    }

    private fun seriesObject(value: JsonValue, index: Int): JsonValue.ObjectValue =
        value.requireObject("Prometheus series at index $index")

    private fun sampleValues(series: JsonValue.ObjectValue, index: Int, matrix: Boolean): SeriesSamples {
        if (matrix) {
            val values = series["values"]?.requireArray("Matrix values for series $index")?.values
            val histograms = series["histograms"]?.requireArray("Matrix histograms for series $index")?.values
            if (values == null && histograms == null) {
                malformed("Matrix series $index is missing values and histograms")
            }
            return SeriesSamples(values.orEmpty(), histograms.orEmpty())
        }
        val value = series["value"]
        val histogram = series["histogram"]
        if ((value == null) == (histogram == null)) {
            malformed("Vector series $index must contain exactly one of value or histogram")
        }
        return if (value != null) SeriesSamples(listOf(value), emptyList()) else {
            SeriesSamples(emptyList(), listOf(requireNotNull(histogram)))
        }
    }

    private fun parseSamplePair(value: JsonValue, context: String): ParsedSample {
        val pair = value.requireArray(context).values
        if (pair.size != 2) malformed("$context must contain exactly a timestamp and value")
        val timestampValue = pair[0] as? JsonValue.NumberValue
            ?: malformed("$context timestamp must be numeric")
        val sampleValue = pair[1].requireString("$context value")
        return ParsedSample(toInstant(timestampValue.value, context), sampleValue)
    }

    private fun parseHistogramPair(
        value: JsonValue,
        context: String,
        cancellation: RequestCancellation,
    ): ParsedHistogramSample {
        val pair = value.requireArray(context).values
        if (pair.size != 2) malformed("$context must contain exactly a timestamp and histogram")
        val timestampValue = pair[0] as? JsonValue.NumberValue
            ?: malformed("$context timestamp must be numeric")
        val histogram = pair[1].requireObject("$context value")
        val countText = histogram["count"]?.requireString("$context count")
            ?: malformed("$context is missing count")
        val sumText = histogram["sum"]?.requireString("$context sum")
            ?: malformed("$context is missing sum")
        val count = parseSample(countText, "$context count")
        val sum = parseSample(sumText, "$context sum")
        val buckets = histogram["buckets"]?.requireArray("$context buckets")?.values.orEmpty()
            .mapIndexed { bucketIndex, rawBucket ->
                cancellation.checkpoint()
                val bucketContext = "$context bucket $bucketIndex"
                val bucket = rawBucket.requireArray(bucketContext).values
                if (bucket.size != 4) {
                    malformed("$bucketContext must contain exactly a boundary rule, left boundary, right boundary, and count")
                }
                val boundaryRule = (bucket[0] as? JsonValue.NumberValue)?.value
                    ?: malformed("$bucketContext boundary rule must be numeric")
                val boundaryRuleValue = try {
                    boundaryRule.intValueExact()
                } catch (_: ArithmeticException) {
                    malformed("$bucketContext boundary rule must be an integer from 0 through 3")
                }
                if (boundaryRuleValue !in 0..3) {
                    malformed("$bucketContext boundary rule must be an integer from 0 through 3")
                }
                val left = bucket[1].requireString("$bucketContext left boundary")
                val right = bucket[2].requireString("$bucketContext right boundary")
                val bucketCount = bucket[3].requireString("$bucketContext count")
                parseSample(left, "$bucketContext left boundary")
                parseSample(right, "$bucketContext right boundary")
                parseSample(bucketCount, "$bucketContext count")
                listOf(boundaryRuleValue, left, right, bucketCount)
            }
        return ParsedHistogramSample(
            timestamp = toInstant(timestampValue.value, context),
            histogram = ParsedHistogram(count, sum, Json.stringify(buckets)),
            nonFiniteValues = listOf(count, sum).count { it == null },
        )
    }

    private fun toInstant(value: BigDecimal, context: String): Instant {
        return try {
            var seconds = value.setScale(0, RoundingMode.FLOOR).longValueExact()
            var nanos = value.subtract(BigDecimal.valueOf(seconds))
                .movePointRight(9)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
            if (nanos == 1_000_000_000L) {
                seconds++
                nanos = 0
            }
            Instant.ofEpochSecond(seconds, nanos)
        } catch (exception: ArithmeticException) {
            throw invalidTimestamp(context, exception)
        } catch (exception: DateTimeException) {
            throw invalidTimestamp(context, exception)
        }
    }

    private fun invalidTimestamp(context: String, cause: RuntimeException): DriverQueryException =
        DriverQueryException(
            DriverQueryException.Category.MALFORMED_RESPONSE,
            "$context contains an invalid timestamp",
            cause,
        )

    private fun parseSample(value: String, context: String): Double? = when (value) {
        "NaN", "StaleNaN", "+Inf", "-Inf" -> null
        else -> value.toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: malformed("$context contains an invalid sample value")
    }

    private fun canonicalSeries(metric: String?, labels: Map<String, String>): String {
        val pairs = labels.asSequence().filter { it.key != "__name__" }.sortedBy { it.key }.toList()
        if (pairs.isEmpty() && metric?.matches(LEGACY_METRIC_NAME) == true) return metric
        return buildString {
            val legacyMetric = metric?.takeIf { it.matches(LEGACY_METRIC_NAME) }
            if (legacyMetric != null) append(legacyMetric)
            append('{')
            var hasEntry = false
            if (metric != null && legacyMetric == null) {
                append('"').append(escapeLabelValue(metric)).append('"')
                hasEntry = true
            }
            pairs.forEachIndexed { index, (name, value) ->
                if (hasEntry || index > 0) append(',')
                if (name.matches(LEGACY_LABEL_NAME)) {
                    append(name)
                } else {
                    append('"').append(escapeLabelValue(name)).append('"')
                }
                append("=\"").append(escapeLabelValue(value)).append('"')
                hasEntry = true
            }
            append('}')
        }
    }

    private fun escapeLabelValue(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                else -> append(character)
            }
        }
    }

    private fun disambiguateLabelColumns(labelNames: Set<String>): List<LabelColumn> {
        val used = standardNames.toMutableSet()
        return labelNames.map { labelName ->
            var outputName = labelName
            while (!used.add(outputName)) outputName = "label_$outputName"
            LabelColumn(labelName, outputName)
        }
    }

    private fun stringArray(
        value: JsonValue?,
        context: String,
        cancellation: RequestCancellation,
    ): List<String> {
        if (value == null) return emptyList()
        return value.requireArray("Prometheus $context").values.mapIndexed { index, item ->
            cancellation.checkpoint()
            safeText(item.requireString("Prometheus $context item $index"))
        }
    }

    private fun maximumJsonNodes(config: DriverConfig): Int = minOf(
        MAX_JSON_NODES,
        (JSON_NODE_OVERHEAD + config.maximumReturnedRows.toLong() * JSON_NODES_PER_ROW)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt(),
    )

    private fun backendError(root: JsonValue.ObjectValue): DriverQueryException {
        val errorType = (root["errorType"] as? JsonValue.StringValue)?.value
        val error = (root["error"] as? JsonValue.StringValue)?.value ?: "unknown backend error"
        val message = buildString {
            append("Mimir query failed")
            if (!errorType.isNullOrBlank()) append(" (").append(safeText(errorType)).append(')')
            append(": ").append(safeText(error))
        }
        val category = when (errorType?.lowercase()) {
            "bad_data" -> DriverQueryException.Category.VALIDATION
            "timeout" -> DriverQueryException.Category.TIMEOUT
            "canceled", "cancelled" -> DriverQueryException.Category.CANCELED
            else -> DriverQueryException.Category.BACKEND
        }
        return DriverQueryException(category, message)
    }

    private fun enforceRows(count: Int, config: DriverConfig) {
        if (count > config.maximumReturnedRows) excessiveRows(config)
    }

    private fun excessiveRows(config: DriverConfig): Nothing = throw DriverQueryException(
        DriverQueryException.Category.GUARDRAIL,
        "Mimir result exceeds the configured ${config.maximumReturnedRows} row limit",
    )

    private fun malformed(message: String): Nothing = throw DriverQueryException(
        DriverQueryException.Category.MALFORMED_RESPONSE,
        message,
    )

    private fun safeText(value: String): String = value.asSequence()
        .map { if (it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20) it else '\uFFFD' }
        .joinToString("")
        .take(2_000)

    private data class ParsedSample(val timestamp: Instant, val value: String)
    private data class ParsedHistogram(val count: Double?, val sum: Double?, val buckets: String)
    private data class ParsedHistogramSample(
        val timestamp: Instant,
        val histogram: ParsedHistogram,
        val nonFiniteValues: Int,
    )
    private data class ParsedSeriesSample(
        val timestamp: Instant,
        val value: Double?,
        val histogram: ParsedHistogram?,
        val sourceOrder: Int,
    )
    private data class SeriesSamples(val values: List<JsonValue>, val histograms: List<JsonValue>)
    private data class LabelColumn(val labelName: String, val outputName: String)

    private const val STANDARD_COLUMN_COUNT = 4
    private const val HISTOGRAM_COLUMN_COUNT = 3
    private const val MAX_DYNAMIC_LABEL_COLUMNS = 256
    private const val MAX_RESULT_CELLS = 2_000_000L
    private const val MAX_JSON_NODES = 1_000_000
    private const val JSON_NODE_OVERHEAD = 10_000L
    private const val JSON_NODES_PER_ROW = 8L
    private val LEGACY_METRIC_NAME = Regex("[a-zA-Z_:][a-zA-Z0-9_:]*")
    private val LEGACY_LABEL_NAME = Regex("[a-zA-Z_][a-zA-Z0-9_]*")
}
