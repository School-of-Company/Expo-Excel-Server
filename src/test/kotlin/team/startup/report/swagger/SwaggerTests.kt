package team.startup.report.swagger

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.report.support.TestClients
import team.startup.report.support.TestJwt
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

abstract class SwaggerTestSupport {
    @Value("\${local.server.port}")
    protected var port: Int = 0

    protected fun request(
        path: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<String> =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build().send(
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$path"))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .apply {
                    headers.forEach { (name, value) ->
                        header(name, value)
                    }
                }.build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    protected fun document() =
        request("/stat/v3/api-docs").let {
            it.statusCode() shouldBe 200
            JSON.readTree(it.body())
        }

    companion object {
        val JSON: JsonMapper = JsonMapper.builder().findAndAddModules().build()

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
            TestClients.register(registry)
        }
    }
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("swagger-test")
class SwaggerDisabledTests : SwaggerTestSupport() {
    @Test
    fun `문서는 환경변수 없이 기본 차단되며 인증한 사용자에게도 열리지 않는다`() {
        val login =
            java.util.Base64
                .getEncoder()
                .encodeToString("developer:expo_report_local".toByteArray())
        listOf("/stat/v3/api-docs", "/stat/v3/api-docs/swagger-config", "/stat/swagger-ui.html", "/stat/swagger-ui/index.html").forEach {
            request(it).statusCode() shouldBe 403
            request(it, headers = mapOf("Authorization" to "Basic $login")).statusCode() shouldBe 403
        }
        request("/excel/standard/test").statusCode() shouldBe 401
        request("/actuator/health").statusCode() shouldBe 200
    }
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["swagger.enabled=true"])
@ActiveProfiles("swagger-test")
class SwaggerEnabledTests : SwaggerTestSupport() {
    @Test
    fun `Host를 내부 인스턴스 주소로 바꾸는 프록시를 거쳐도 서버 URL은 상대 경로다`() {
        val proxy = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        proxy.createContext("/stat/v3/api-docs") { exchange ->
            exchange.use {
                val upstream =
                    request(
                        "/stat/v3/api-docs",
                        headers =
                            mapOf(
                                "X-Forwarded-Host" to "docs.example.test",
                                "X-Forwarded-Proto" to "https",
                            ),
                    )
                val bytes = upstream.body().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(upstream.statusCode(), bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        proxy.start()
        try {
            val response =
                HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:${proxy.address.port}/stat/v3/api-docs")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            response.statusCode() shouldBe 200
            JSON.readTree(response.body()).path("servers").toString() shouldBe """[{"url":"/"}]"""
        } finally {
            proxy.stop(0)
        }
    }

    @Test
    fun `공개 문서는 내부 경로 전용 스키마를 제거하고 공용 중첩 스키마를 유지한다`() {
        val api = document()
        api.path("info").path("title").asString() shouldBe "Expo Report Server API"
        api.path("paths").has("/internal/test") shouldBe false
        api.path("paths").has("/internal") shouldBe false
        val schemas = api.path("components").path("schemas")
        schemas.has("InternalOnly") shouldBe false
        schemas.has("SharedPayload") shouldBe true
        schemas.has("DownloadRequest") shouldBe true
        schemas.has("ApplicationFilter") shouldBe true
        schemas.has("ErrorResponse") shouldBe true
        api.path("components").path("securitySchemes").has("internalToken") shouldBe false
    }

    @Test
    fun `모든 XLSX 경로는 관리자 JWT 오류코드 바이너리 응답과 한국어 요청 스키마를 문서화한다`() {
        val api = document()
        val old = api.path("paths").path("/excel/program/{expo_id}").path("get")
        listOf("200", "400", "401", "403", "500").forEach { old.path("responses").has(it) shouldBe true }
        val download = api.path("paths").path("/excel/expos/{expoId}/downloads").path("post")
        listOf("200", "400", "401", "403", "404", "410", "413", "500", "502").forEach { download.path("responses").has(it) shouldBe true }
        download.path("security").get(0).has("bearerAuth") shouldBe true
        download
            .path("responses")
            .path("200")
            .path("content")
            .path("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            .path("schema")
            .path("format")
            .asString() shouldBe
            "binary"
        val filter =
            api
                .path("components")
                .path("schemas")
                .path("ApplicationFilter")
                .path("properties")
        filter
            .path("search")
            .path("description")
            .asString()
            .contains("회차") shouldBe true
        filter.path("search").path("example").asString() shouldBe "예시 강연"
    }

    @Test
    fun `Swagger UI 이동과 정적 리소스 설정도 stat 접두사에서 열린다`() {
        val redirect = request("/stat/swagger-ui.html")
        redirect.statusCode() shouldBe 302
        val location = redirect.headers().firstValue("Location").orElseThrow()
        location.contains("/stat/swagger-ui/") shouldBe true
        request(URI(location).let { if (it.isAbsolute) it.rawPath else location }).statusCode() shouldBe 200
        request("/stat/swagger-ui/swagger-ui.css").statusCode() shouldBe 200
        request("/stat/v3/api-docs/swagger-config").statusCode() shouldBe 200
    }

    @Test
    fun `문서 경로는 GET만 열고 Excel과 기존 모니터링 인증은 유지한다`() {
        listOf("HEAD", "POST", "PUT", "DELETE").forEach { method -> request("/stat/v3/api-docs", method).statusCode() shouldBe 403 }
        request("/excel/standard/test").statusCode() shouldBe 401
        request("/stat/other").statusCode() shouldBe 401
        request("/v3/api-docs").statusCode() shouldBe 401
        request("/actuator/health").statusCode() shouldBe 200
    }
}

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["swagger.enabled=true", "swagger.include-internal=true"],
)
@ActiveProfiles("swagger-test")
class SwaggerIncludeInternalTests : SwaggerTestSupport() {
    @Test
    fun `내부 포함을 켜면 내부 문서에 토큰 스키마를 붙이되 실제 인증은 열지 않는다`() {
        val api = document()
        api
            .path("paths")
            .path("/internal/test")
            .path("get")
            .path("security")
            .get(0)
            .has("internalToken") shouldBe true
        api
            .path("paths")
            .path("/internal")
            .path("get")
            .path("security")
            .get(0)
            .has("internalToken") shouldBe true
        api.path("components").path("schemas").has("InternalOnly") shouldBe true
        val scheme = api.path("components").path("securitySchemes").path("internalToken")
        scheme.path("type").asString() shouldBe "apiKey"
        scheme.path("in").asString() shouldBe "header"
        scheme.path("name").asString() shouldBe "X-Internal-Token"
        scheme.has("example") shouldBe false
        request("/internal/test").statusCode() shouldBe 401
    }
}

@RestController
@Profile("swagger-test")
class SwaggerFixtureController {
    @GetMapping("/stat/test-public")
    fun publicFixture() = PublicPayload(SharedPayload("example"))

    @GetMapping("/internal/test", "/internal")
    fun internalFixture() = InternalOnly(SharedPayload("example"))
}

data class SharedPayload(
    val value: String,
)

data class PublicPayload(
    val shared: SharedPayload,
)

data class InternalOnly(
    val shared: SharedPayload,
)
