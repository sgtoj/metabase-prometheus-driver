package io.cruxstack.metabase.prometheus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class PromDurationTest {
    @ParameterizedTest
    @CsvSource(
        "15s, 15s",
        "90s, 1m30s",
        "1h30m, 1h30m",
        "7d, 1w",
        "1y2w3d4h5m6s7ms, 1y2w3d4h5m6s7ms",
    )
    fun `parses and canonically formats durations`(input: String, expected: String) {
        assertEquals(expected, PromDuration.parse(input).format())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "1", "1q", "-1s", "1.5s", "1s garbage", "ms", "1s1h", "1m1m"])
    fun `rejects invalid durations`(input: String) {
        assertThrows(IllegalArgumentException::class.java) { PromDuration.parse(input) }
    }
}
