package io.cruxstack.metabase.prometheus

data class BackendVersion(
    val flavor: String,
    val version: String,
)

class PrometheusClient(
    private val config: DriverConfig,
    userAgentSuffix: String = "query",
) : AutoCloseable {
    private val transport = HttpTransport(config, "$USER_AGENT $userAgentSuffix")

    fun execute(
        query: CompiledQuery,
        cancellation: RequestCancellation = RequestCancellation(),
    ): NormalizedResult = withRedactedErrors {
        cancellation.start(config.queryTimeout)
        val call = when (query.mode) {
            Directive.Mode.INSTANT -> HttpCall(
                HttpCall.Method.POST,
                config.endpoint("/api/v1/query"),
                query.formParameters(config.queryTimeout),
            )
            Directive.Mode.RANGE -> HttpCall(
                HttpCall.Method.POST,
                config.endpoint("/api/v1/query_range"),
                query.formParameters(config.queryTimeout),
            )
            Directive.Mode.LABEL_VALUES -> HttpCall(
                HttpCall.Method.GET,
                config.endpoint("/api/v1/label/${encodeLabelName(requireNotNull(query.label))}/values"),
                query.formParameters(),
            )
        }
        val payload = transport.execute(call, cancellation)
        if (payload.statusCode !in 200..299) throw httpError(payload, query.mode, cancellation)
        ResultConverter.convert(payload.body, query.mode, config, payload.byteCount, cancellation)
    }

    fun buildInfo(): BackendVersion? = withRedactedErrors {
        val cancellation = RequestCancellation()
        val payload = transport.execute(
            HttpCall(HttpCall.Method.GET, config.endpoint("/api/v1/status/buildinfo")),
            cancellation,
        )
        if (payload.statusCode == 404) return@withRedactedErrors null
        if (payload.statusCode !in 200..299) {
            throw DriverQueryException(
                DriverQueryException.Category.HTTP,
                "Mimir build information request returned HTTP ${payload.statusCode}",
            )
        }
        val root = try {
            Json.parse(payload.body, MAX_BUILD_INFO_JSON_NODES, cancellation::checkpoint)
                .requireObject("Build information response")
        } catch (exception: JsonParsingLimitException) {
            throw DriverQueryException(
                DriverQueryException.Category.GUARDRAIL,
                "Mimir build information exceeds the JSON structural limit",
                exception,
            )
        } catch (exception: IllegalArgumentException) {
            throw DriverQueryException(
                DriverQueryException.Category.MALFORMED_RESPONSE,
                "Mimir returned malformed build information",
                exception,
            )
        }
        try {
            if (root["status"]?.requireString("Build information status") != "success") {
                return@withRedactedErrors null
            }
            val data = root["data"]?.requireObject("Build information data") ?: return@withRedactedErrors null
            val version = (data["version"] as? JsonValue.StringValue)?.value ?: return@withRedactedErrors null
            val flavor = (data["application"] as? JsonValue.StringValue)?.value
                ?: (data["name"] as? JsonValue.StringValue)?.value
                ?: "Prometheus"
            BackendVersion(flavor, version)
        } catch (exception: IllegalArgumentException) {
            throw DriverQueryException(
                DriverQueryException.Category.MALFORMED_RESPONSE,
                "Mimir returned malformed build information",
                exception,
            )
        }
    }

    private fun <T> withRedactedErrors(block: () -> T): T = try {
        block()
    } catch (exception: DriverQueryException) {
        throw config.redact(exception)
    }

    private fun httpError(
        payload: HttpPayload,
        mode: Directive.Mode,
        cancellation: RequestCancellation,
    ): DriverQueryException {
        try {
            ResultConverter.convert(payload.body, mode, config, payload.byteCount, cancellation)
        } catch (exception: DriverQueryException) {
            if (
                exception.category == DriverQueryException.Category.BACKEND ||
                exception.category == DriverQueryException.Category.VALIDATION ||
                exception.category == DriverQueryException.Category.TIMEOUT ||
                exception.category == DriverQueryException.Category.CANCELED
            ) {
                return exception
            }
        }
        return DriverQueryException(
            DriverQueryException.Category.HTTP,
            "Mimir request returned HTTP ${payload.statusCode}",
        )
    }

    companion object {
        private val LEGACY_LABEL_NAME = Regex("[a-zA-Z_][a-zA-Z0-9_]*")
        private const val MAX_BUILD_INFO_JSON_NODES = 1_000

        val USER_AGENT: String = "metabase-prometheus-driver/" +
            (PrometheusClient::class.java.`package`.implementationVersion ?: "development")

        internal fun encodeLabelName(name: String): String {
            if (LEGACY_LABEL_NAME.matches(name)) return name
            return buildString {
                append("U__")
                var index = 0
                while (index < name.length) {
                    val codePoint = name.codePointAt(index)
                    when {
                        codePoint == '_'.code -> append("__")
                        codePoint in 'a'.code..'z'.code ||
                            codePoint in 'A'.code..'Z'.code ||
                            codePoint in '0'.code..'9'.code ||
                            codePoint == ':'.code -> appendCodePoint(codePoint)
                        else -> append('_').append(codePoint.toString(16)).append('_')
                    }
                    index += Character.charCount(codePoint)
                }
            }
        }
    }

    override fun close() {
        transport.close()
    }
}
