package io.cruxstack.metabase.prometheus

import java.net.URI
import java.time.Duration

data class DriverConfig(
    val baseUri: URI,
    val tenantId: String?,
    val authentication: Authentication,
    val connectTimeout: Duration,
    val queryTimeout: Duration,
    val maximumQueryRange: Duration,
    val maximumDataPoints: Int,
    val minimumRangeStep: Duration,
    val maximumReturnedRows: Int,
    val maximumResponseBytes: Int,
) {
    sealed interface Authentication {
        data object None : Authentication
        data class Basic(val username: String, val password: String) : Authentication {
            override fun toString(): String = "Basic(username=$username, password=<redacted>)"
        }
        data class Bearer(val token: String) : Authentication {
            override fun toString(): String = "Bearer(token=<redacted>)"
        }
    }

    fun endpoint(apiPath: String): URI {
        require(apiPath.startsWith('/')) { "API path must start with '/'" }
        return URI.create(baseUri.toASCIIString().trimEnd('/') + apiPath)
    }

    internal fun forConnectionTest(): DriverConfig = copy(
        connectTimeout = minOf(connectTimeout, CONNECTION_TEST_CONNECT_TIMEOUT),
        queryTimeout = minOf(queryTimeout, CONNECTION_TEST_QUERY_TIMEOUT),
        maximumDataPoints = 1,
        maximumReturnedRows = 1,
        maximumResponseBytes = minOf(maximumResponseBytes, CONNECTION_TEST_MAXIMUM_RESPONSE_BYTES),
    )

    internal fun redact(exception: DriverQueryException): DriverQueryException {
        val secrets = when (val configured = authentication) {
            Authentication.None -> emptyList()
            is Authentication.Basic -> listOf(configured.password)
            is Authentication.Bearer -> listOf(configured.token)
        }.filter(String::isNotEmpty)
        if (secrets.isEmpty()) return exception

        val message = exception.message ?: "Mimir request failed"
        val redactedMessage = secrets.fold(message) { value, secret -> value.replace(secret, "<redacted>") }
        val causeContainsSecret = generateSequence<Throwable>(exception) { it.cause }
            .any { cause -> secrets.any { secret -> cause.message?.contains(secret) == true } }
        if (redactedMessage == message && !causeContainsSecret) return exception
        return DriverQueryException(exception.category, redactedMessage)
    }

    companion object {
        const val DEFAULT_MAXIMUM_DATA_POINTS = 1_100
        const val DEFAULT_MAXIMUM_RETURNED_ROWS = 100_000
        const val DEFAULT_MAXIMUM_RESPONSE_BYTES = 32 * 1024 * 1024

        private val CONNECTION_TEST_CONNECT_TIMEOUT = Duration.ofSeconds(3)
        private val CONNECTION_TEST_QUERY_TIMEOUT = Duration.ofSeconds(5)
        private const val CONNECTION_TEST_MAXIMUM_RESPONSE_BYTES = 64 * 1024
        private val INSECURE_TLS_KEYS = setOf(
            "disable-tls-verification",
            "skip-tls-verify",
            "tls-skip-verify",
            "insecure",
        )

        @JvmStatic
        fun from(details: Map<*, *>): DriverConfig {
            val values = details.entries.associate { keyName(it.key) to it.value }
            rejectInsecureTlsOptions(values)
            val baseUri = normalizeBaseUri(requiredText(values, "url", "Base URL"))
            val tenantId = optionalHeaderText(values, "tenant-id", "Tenant ID")?.also {
                require(',' !in it && '|' !in it) {
                    "Tenant ID cannot contain federation separators ',' or '|'"
                }
            }
            val authMode = optionalText(values, "auth-mode") ?: "none"
            val authentication = when (authMode) {
                "none" -> Authentication.None
                "basic" -> Authentication.Basic(
                    requiredRawText(values, "username", "Username").also {
                        require(':' !in it) { "Username cannot contain ':' for Basic authentication" }
                    },
                    requiredRawText(values, "password", "Password"),
                )
                "bearer" -> Authentication.Bearer(
                    requiredRawText(values, "bearer-token", "Bearer token").also {
                        validateHeaderValue("Bearer token", it)
                    },
                )
                else -> throw IllegalArgumentException("Unsupported authentication mode: $authMode")
            }

            return DriverConfig(
                baseUri = baseUri,
                tenantId = tenantId,
                authentication = authentication,
                connectTimeout = positiveDuration(values, "connect-timeout", "10s"),
                queryTimeout = positiveDuration(values, "query-timeout", "120s"),
                maximumQueryRange = positiveDuration(values, "maximum-query-range", "31d"),
                maximumDataPoints = positiveInt(values, "maximum-data-points", DEFAULT_MAXIMUM_DATA_POINTS),
                minimumRangeStep = positiveDuration(values, "minimum-range-step", "15s"),
                maximumReturnedRows = positiveInt(values, "maximum-returned-rows", DEFAULT_MAXIMUM_RETURNED_ROWS),
                maximumResponseBytes = positiveInt(values, "maximum-response-size", DEFAULT_MAXIMUM_RESPONSE_BYTES),
            )
        }

        private fun normalizeBaseUri(value: String): URI {
            val uri = try {
                URI(value)
            } catch (exception: Exception) {
                throw IllegalArgumentException("Base URL is invalid", exception)
            }
            require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
                "Base URL must use HTTP or HTTPS"
            }
            require(!uri.host.isNullOrBlank()) { "Base URL must include a host" }
            require(uri.userInfo == null) { "Base URL must not contain user information" }
            require(uri.query == null && uri.fragment == null) { "Base URL must not contain a query or fragment" }
            return URI.create(value.trim().trimEnd('/'))
        }

        private fun positiveDuration(values: Map<String, Any?>, key: String, default: String): Duration {
            val duration = PromDuration.parse(optionalText(values, key) ?: default).duration
            require(!duration.isZero && !duration.isNegative) { "$key must be greater than zero" }
            return duration
        }

        private fun positiveInt(values: Map<String, Any?>, key: String, default: Int): Int {
            val raw = values[key] ?: return default
            val value = when (raw) {
                is Number -> raw.toInt()
                else -> raw.toString().toIntOrNull()
            }
            require(value != null && value > 0) { "$key must be a positive integer" }
            return value
        }

        private fun requiredText(values: Map<String, Any?>, key: String, displayName: String): String =
            optionalText(values, key) ?: throw IllegalArgumentException("$displayName is required")

        private fun requiredRawText(values: Map<String, Any?>, key: String, displayName: String): String =
            values[key]?.toString()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("$displayName is required")

        private fun optionalHeaderText(
            values: Map<String, Any?>,
            key: String,
            displayName: String,
        ): String? {
            val value = values[key]?.toString() ?: return null
            validateHeaderValue(displayName, value)
            return value.trim().takeIf(String::isNotEmpty)
        }

        private fun optionalText(values: Map<String, Any?>, key: String): String? =
            values[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        private fun validateHeaderValue(name: String, value: String) {
            require(value.all { it.code in 0x20..0x7e }) { "$name must contain printable ASCII characters only" }
        }

        private fun rejectInsecureTlsOptions(values: Map<String, Any?>) {
            val key = values.entries.firstOrNull { (key, value) ->
                key.lowercase() in INSECURE_TLS_KEYS && isTruthy(value)
            }?.key ?: return
            throw IllegalArgumentException(
                "Insecure TLS option '$key' is not supported; certificate and hostname verification cannot be disabled",
            )
        }

        private fun isTruthy(value: Any?): Boolean = when (value) {
            null -> false
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            else -> value.toString().trim().lowercase() !in setOf("", "false", "0", "no", "off", "nil", "null")
        }

        private fun keyName(key: Any?): String = key.toString().removePrefix(":")
    }
}
