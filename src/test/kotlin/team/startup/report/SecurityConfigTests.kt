package team.startup.report

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import team.startup.report.support.TestClients
import team.startup.report.support.TestJwt
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "management.endpoints.web.exposure.include=health,prometheus,info",
        "management.endpoint.health.probes.enabled=true",
    ],
)
class SecurityConfigTests {
    @Value("\${local.server.port}")
    private var port: Int = 0

    private val client = HttpClient.newHttpClient()

    @Test
    fun `monitoring GET endpoints allow anonymous requests`() {
        listOf("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").forEach { path ->
            val response = request(path)
            response.statusCode() shouldBe 200
            response.body() shouldContain "\"status\":\"UP\""
        }
        val metrics = request("/actuator/prometheus")
        metrics.statusCode() shouldBe 200
        metrics.body() shouldContain "jvm_memory_used_bytes"
    }

    @Test
    fun `GC pause time is exported as an SLO bucket histogram`() {
        // GC 알림은 비동기로 기록되므로 버킷이 보일 때까지 잠깐 다시 긁는다
        System.gc()
        val bucket = Regex("""jvm_gc_pause_seconds_bucket\{[^}]*le="0\.05"[^}]*}""")
        val deadline = System.nanoTime() + 10_000_000_000
        var body = request("/actuator/prometheus").body()
        while (!bucket.containsMatchIn(body) && System.nanoTime() < deadline) {
            Thread.sleep(100)
            body = request("/actuator/prometheus").body()
        }
        bucket.containsMatchIn(body) shouldBe true
    }

    @Test
    fun `other endpoints still require authentication`() {
        listOf("/actuator/info", "/v3/api-docs", "/actuator/prometheus/other", "/unknown").forEach { path ->
            request(path).statusCode() shouldBe 401
        }
    }

    @Test
    fun `anonymous monitoring errors keep their real status`() {
        request("/actuator/health/unknown").statusCode() shouldBe 404
    }

    @Test
    fun `monitoring paths do not allow anonymous HEAD requests`() {
        listOf("/actuator/health", "/actuator/health/liveness", "/actuator/prometheus").forEach { path ->
            request(path, "HEAD").statusCode() shouldBe 401
        }
    }

    private fun request(
        path: String,
        method: String = "GET",
    ): HttpResponse<String> =
        client.send(
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$path"))
                .header("Accept", if (path == "/actuator/prometheus" && method == "GET") "text/plain" else "application/json")
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun jwt(registry: DynamicPropertyRegistry) {
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
            TestClients.register(registry)
        }
    }
}
