package io.cruxstack.metabase.prometheus

import java.math.BigDecimal

sealed interface ParameterValue {
    data class Text(val value: String) : ParameterValue
    data class TextList(val values: List<String>) : ParameterValue
    data class Number(val value: BigDecimal) : ParameterValue
    data class BooleanValue(val value: Boolean) : ParameterValue
    data class TimeRangeValue(val value: QueryTimeRange) : ParameterValue
    data object Missing : ParameterValue

    fun hasValue(): Boolean = when (this) {
        is Text -> value.isNotEmpty()
        is TextList -> values.isNotEmpty()
        is Number, is BooleanValue, is TimeRangeValue -> true
        Missing -> false
    }
}
