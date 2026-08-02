package io.cruxstack.metabase.prometheus

import java.time.Duration

@JvmInline
value class PromDuration private constructor(val duration: Duration) {
    fun format(): String {
        var milliseconds = duration.toMillis()
        require(milliseconds >= 0) { "Duration cannot be negative" }
        if (milliseconds == 0L) return "0s"

        val units = listOf(
            "y" to 365L * 24 * 60 * 60 * 1_000,
            "w" to 7L * 24 * 60 * 60 * 1_000,
            "d" to 24L * 60 * 60 * 1_000,
            "h" to 60L * 60 * 1_000,
            "m" to 60L * 1_000,
            "s" to 1_000L,
            "ms" to 1L,
        )
        return buildString {
            for ((suffix, unitMillis) in units) {
                val count = milliseconds / unitMillis
                if (count > 0) {
                    append(count).append(suffix)
                    milliseconds %= unitMillis
                }
            }
        }
    }

    companion object {
        private val component = Regex("([0-9]+)(ms|[smhdwy])")
        private val unitMillis = mapOf(
            "ms" to 1L,
            "s" to 1_000L,
            "m" to 60_000L,
            "h" to 3_600_000L,
            "d" to 86_400_000L,
            "w" to 604_800_000L,
            "y" to 31_536_000_000L,
        )
        private val unitOrder = mapOf("y" to 0, "w" to 1, "d" to 2, "h" to 3, "m" to 4, "s" to 5, "ms" to 6)

        fun parse(value: String): PromDuration {
            require(value.isNotEmpty()) { "Duration cannot be empty" }
            var index = 0
            var total = 0L
            var previousUnit = -1
            while (index < value.length) {
                val match = component.find(value, index)
                require(match != null && match.range.first == index) { "Invalid Prometheus duration: $value" }
                val count = match.groupValues[1].toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid Prometheus duration: $value")
                val unit = match.groupValues[2]
                val order = unitOrder.getValue(unit)
                require(order > previousUnit) { "Prometheus duration units must be unique and ordered: $value" }
                previousUnit = order
                val multiplier = unitMillis.getValue(unit)
                total = try {
                    Math.addExact(total, Math.multiplyExact(count, multiplier))
                } catch (exception: ArithmeticException) {
                    throw IllegalArgumentException("Prometheus duration is too large: $value", exception)
                }
                index = match.range.last + 1
            }
            return PromDuration(Duration.ofMillis(total))
        }

        fun of(duration: Duration): PromDuration {
            require(!duration.isNegative) { "Duration cannot be negative" }
            return PromDuration(duration)
        }
    }
}
