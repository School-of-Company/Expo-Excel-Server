package team.startup.report.swagger

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
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
            val response = request(it)
            response.statusCode() shouldBe 403
            response
                .headers()
                .firstValue("Content-Type")
                .orElse("")
                .startsWith("application/json") shouldBe true
            JSON.readTree(response.body()) shouldBe JSON.readTree("""{"status":403,"message":"접근 권한이 없습니다."}""")
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
    fun `모든 XLSX 경로는 관리자 JWT 오류코드 바이너리 응답과 한국어 요청 스키마를 문서화한다`() {
        val api = document()
        listOf("/excel/{expo_id}", "/excel/standard/{expo_id}", "/excel/trainee/{trainee_id}").forEach { path ->
            val responses =
                api
                    .path("paths")
                    .path(path)
                    .path("get")
                    .path("responses")
            listOf("404", "502").forEach { responses.has(it) shouldBe true }
        }
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
    fun `생성된 다운로드 요청 스키마는 양식과 범위를 필수로 표시한다`() {
        val required =
            document()
                .path("components")
                .path("schemas")
                .path("DownloadRequest")
                .path("required")
        required.values().map { it.asString() }.toSet() shouldBe setOf("template", "scope")
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
