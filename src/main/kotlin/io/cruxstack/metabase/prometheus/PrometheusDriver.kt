package io.cruxstack.metabase.prometheus

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.security.MessageDigest
import java.time.Instant

/** JVM facade kept deliberately small so the Clojure Metabase adapter stays version-focused. */
object PrometheusDriver {
    @JvmStatic
    fun validateConfig(details: Map<*, *>) {
        DriverConfig.from(details)
    }

    @JvmStatic
    fun canConnect(details: Map<*, *>): Boolean {
        val config = DriverConfig.from(details).forConnectionTest()
        val query = CompiledQuery(
            mode = Directive.Mode.INSTANT,
            promQl = "vector(1)",
            timeRange = QueryTimeRange(Instant.EPOCH, Instant.EPOCH),
            step = null,
            label = null,
        )
        PrometheusClient(config, "connection-test").use { it.execute(query) }
        return true
    }

    @JvmStatic
    fun dbmsVersion(details: Map<*, *>): BackendVersion = runCatching {
        val config = DriverConfig.from(details)
        PrometheusClient(config, "version-check").use { it.buildInfo() }
    }.getOrNull() ?: BackendVersion("Prometheus", "unknown")

    @JvmStatic
    fun substituteNativeQuery(
        nativeQuery: String,
        templateTags: Any?,
        parameters: Any?,
        timezoneId: String,
    ): String = MetabaseParameters.substitute(nativeQuery, templateTags, parameters, timezoneId)

    @JvmStatic
    fun startQuery(details: Map<*, *>, nativeQuery: String): RunningQuery {
        val config = DriverConfig.from(details)
        val compiled = try {
            QueryCompiler.compile(nativeQuery, emptyMap(), config)
        } catch (exception: DriverQueryException) {
            throw exception
        } catch (exception: IllegalArgumentException) {
            throw DriverQueryException(
                DriverQueryException.Category.VALIDATION,
                exception.message ?: "Invalid native PromQL query",
                exception,
            )
        } catch (exception: RuntimeException) {
            // Compilation is pure query validation, so an unexpected failure must still surface as
            // an actionable query error instead of an opaque driver stack trace.
            throw DriverQueryException(
                DriverQueryException.Category.VALIDATION,
                "Could not compile the native PromQL query",
                exception,
            )
        }
        val cancellation = RequestCancellation()
        val queryHash = queryHash(compiled.promQl)
        val future = CompletableFuture<NormalizedResult>()
        val worker = Thread.ofVirtual().name("metabase-prometheus-query-${queryHash.take(12)}").start {
            try {
                future.complete(PrometheusClient(config).use { it.execute(compiled, cancellation) })
            } catch (exception: Throwable) {
                future.completeExceptionally(exception)
            }
        }
        return RunningQuery(
            future = future,
            cancellation = cancellation,
            worker = worker,
            queryHash = queryHash,
            mode = compiled.mode.wireValue,
        )
    }

    @JvmStatic
    fun queryHash(promQl: String): String = MessageDigest.getInstance("SHA-256")
        .digest(promQl.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

class RunningQuery internal constructor(
    private val future: CompletableFuture<NormalizedResult>,
    private val cancellation: RequestCancellation,
    private val worker: Thread,
    val queryHash: String,
    val mode: String,
) {
    fun await(): NormalizedResult {
        return try {
            future.join()
        } catch (exception: CancellationException) {
            throw DriverQueryException(DriverQueryException.Category.CANCELED, "Mimir query was canceled", exception)
        } catch (exception: CompletionException) {
            val cause = exception.cause
            if (cause is RuntimeException) throw cause
            throw DriverQueryException(
                DriverQueryException.Category.CONNECTION,
                "Mimir query failed",
                cause ?: exception,
            )
        }
    }

    fun cancel() {
        cancellation.cancel()
        worker.interrupt()
        future.cancel(false)
    }
}
