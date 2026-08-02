package io.cruxstack.metabase.prometheus

class DriverQueryException(
    val category: Category,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    enum class Category {
        VALIDATION,
        GUARDRAIL,
        TIMEOUT,
        CANCELED,
        CONNECTION,
        HTTP,
        BACKEND,
        MALFORMED_RESPONSE,
    }
}
