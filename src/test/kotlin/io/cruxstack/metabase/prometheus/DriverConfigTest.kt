package io.cruxstack.metabase.prometheus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration

class DriverConfigTest {
    @Test
    fun `normalizes a Mimir base path without changing its authority`() {
        val config = DriverConfig.from(mapOf("url" to "https://metrics.example.test/prometheus/"))
        assertEquals(URI("https://metrics.example.test/prometheus"), config.baseUri)
        assertEquals(
            URI("https://metrics.example.test/prometheus/api/v1/query"),
            config.endpoint("/api/v1/query"),
        )
        assertEquals(Duration.ofSeconds(10), config.connectTimeout)

        val encoded = DriverConfig.from(mapOf("url" to "https://metrics.example.test/prefix%20with%20spaces/"))
        assertEquals(
            URI("https://metrics.example.test/prefix%20with%20spaces/api/v1/query"),
            encoded.endpoint("/api/v1/query"),
        )
        assertEquals("HTTP", DriverConfig.from(mapOf("url" to "HTTP://localhost")).baseUri.scheme)
    }

    @Test
    fun `accepts Clojure-style keyword keys`() {
        val config = DriverConfig.from(mapOf(":url" to "http://localhost:9009", ":tenant-id" to "test-tenant"))
        assertEquals("test-tenant", config.tenantId)
    }

    @Test
    fun `preserves credential whitespace`() {
        val config = DriverConfig.from(
            mapOf(
                "url" to "http://localhost",
                "auth-mode" to "basic",
                "username" to " user ",
                "password" to " password ",
            ),
        )
        assertEquals(DriverConfig.Authentication.Basic(" user ", " password "), config.authentication)
    }

    @Test
    fun `rejects unsafe URLs and headers`() {
        assertThrows(IllegalArgumentException::class.java) {
            DriverConfig.from(mapOf("url" to "file:///tmp/data"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DriverConfig.from(mapOf("url" to "http://localhost:9009", "tenant-id" to "safe\r\nInjected: yes"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DriverConfig.from(mapOf("url" to "http://localhost:9009", "tenant-id" to "test-first,test-second"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DriverConfig.from(mapOf("url" to "http://localhost:9009", "tenant-id" to "test-first|test-second"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DriverConfig.from(mapOf("url" to "http://localhost:9009", "tenant-id" to "\ttest-tenant"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DriverConfig.from(mapOf("url" to "http://localhost:9009", "tenant-id" to "test-tenant\u007f"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DriverConfig.from(
                mapOf(
                    "url" to "http://localhost:9009",
                    "auth-mode" to "basic",
                    "username" to "bad:user",
                    "password" to "password",
                ),
            )
        }
    }

    @Test
    fun `rejects truthy insecure TLS options and accepts explicit false values`() {
        val insecureKeys = listOf(
            "disable-tls-verification",
            "skip-tls-verify",
            "tls-skip-verify",
            "insecure",
        )
        insecureKeys.forEach { key ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                DriverConfig.from(mapOf("url" to "https://metrics.example.test", key to true))
            }
            assertFalse(error.message.orEmpty().contains("true"))
            assertFalse(error.message.orEmpty().contains("metrics.example.test"))
        }

        insecureKeys.forEach { key ->
            DriverConfig.from(mapOf("url" to "https://metrics.example.test", key to false))
            DriverConfig.from(mapOf("url" to "https://metrics.example.test", key to "off"))
        }
    }

    @Test
    fun `bounds connection test configuration independently of query settings`() {
        val config = DriverConfig.from(
            mapOf(
                "url" to "https://metrics.example.test/prometheus",
                "connect-timeout" to "30s",
                "query-timeout" to "10m",
                "maximum-data-points" to 50_000,
                "maximum-returned-rows" to 50_000,
                "maximum-response-size" to 8 * 1024 * 1024,
            ),
        ).forConnectionTest()

        assertEquals(Duration.ofSeconds(3), config.connectTimeout)
        assertEquals(Duration.ofSeconds(5), config.queryTimeout)
        assertEquals(1, config.maximumDataPoints)
        assertEquals(1, config.maximumReturnedRows)
        assertEquals(64 * 1024, config.maximumResponseBytes)
    }

    @Test
    fun `query logs use a stable hash rather than raw PromQL`() {
        assertEquals(
            "c2f13ff7c1677ac9fbe0669f439ed3e0bfb05030a90c5bf9f248a02eb4a981bd",
            PrometheusDriver.queryHash("vector(1)"),
        )
    }

    @Test
    fun `redacts credentials and rejects control characters in raw headers`() {
        val basic = DriverConfig.from(
            mapOf(
                "url" to "http://localhost",
                "auth-mode" to "basic",
                "username" to "alice",
                "password" to "top-secret",
            ),
        )
        assertFalse(basic.toString().contains("top-secret"))
        assertFalse(basic.authentication.toString().contains("top-secret"))

        val bearer = DriverConfig.from(
            mapOf(
                "url" to "http://localhost",
                "auth-mode" to "bearer",
                "bearer-token" to "private-token",
            ),
        )
        assertFalse(bearer.toString().contains("private-token"))
        assertFalse(bearer.authentication.toString().contains("private-token"))

        val bearerError = assertThrows(IllegalArgumentException::class.java) {
            DriverConfig.from(
                mapOf(
                    "url" to "http://localhost",
                    "auth-mode" to "bearer",
                    "bearer-token" to "unsafe\u0000token",
                ),
            )
        }
        assertEquals("Bearer token must contain printable ASCII characters only", bearerError.message)
    }
}
