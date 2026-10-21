package team.startup.report.security

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import team.startup.report.global.client.StubProvider
import team.startup.report.support.TestJwt
import team.startup.report.support.values
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private const val EVENT = "11111111-1111-1111-1111-111111111111"
private const val ACCESS = "GET /internal/expo/$EVENT/admin-access"
private const val ANSWERS = "GET /internal/surveys/$EVENT/public-answers?limit=1000"

/** 공개 설문 응답 엑셀의 권한 검사와 실제 연결(행사·설문 공급자 스텁). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicSurveyExcelAccessTests {
    @Value("\${local.server.port}")
    private var port: Int = 0

    @BeforeEach
    fun setup() {
        listOf(user, expo, form).forEach {
            it.routes.clear()
            it.requests.clear()
        }
        expo.on(
            ACCESS,
            body =
                mapOf(
                    "expoId" to EVENT,
                    "adminId" to 1,
                    "permission" to "APPLICATION_READ",
                    "policyVersion" to "GLOBAL_ADMIN_APPLICATION_READ_V1",
                    "scope" to "GLOBAL_ADMIN",
                ),
        )
    }

    @Test
    fun `관리자는 공개 설문 응답 엑셀을 내려받고 한글 파일명이 인코딩된다`() {
        form.on(
            ANSWERS,
            body =
                mapOf(
                    "survey" to mapOf("id" to "s1", "expoId" to EVENT, "title" to "만족도 조사"),
                    "questionDefinition" to "CURRENT",
                    "questions" to listOf(mapOf("id" to 17, "title" to "의견", "formType" to "SENTENCE")),
                    "snapshotAt" to "2026-10-21T05:00:00Z",
                    "totalCount" to 1,
                    "answers" to
                        listOf(
                            mapOf(
                                "id" to "a1",
                                "createdAt" to "2026-10-21T04:00:00Z",
                                "occupation" to "GENERAL",
                                "answers" to mapOf("17" to "좋아요"),
                            ),
                        ),
                    "nextCursor" to null,
                ),
        )

        val response = send("/excel/public-surveys/$EVENT")

        response.statusCode() shouldBe 200
        response.headers().firstValue("Content-Disposition").get() shouldContain
            "filename*=UTF-8''%EB%A7%8C%EC%A1%B1%EB%8F%84%EC%A1%B0%EC%82%AC%EC%9D%91%EB%8B%B5_"
        response.headers().firstValue("Cache-Control").get() shouldContain "no-store"
        XSSFWorkbook(ByteArrayInputStream(response.body())).use {
            it.getSheet("응답").values()[1] shouldBe listOf("a1", "s1", "만족도 조사", "2026-10-21 13:00:00", "일반인", "좋아요")
        }
        form.requests.single().token shouldBe "form-secret"
        expo.requests.single().authorization shouldBe "Bearer $token"
        user.requests.size shouldBe 0
    }

    @Test
    fun `권한 거절과 JWT 실패는 설문 서비스를 조회하지 않는다`() {
        expo.on(ACCESS, 403, "private-body")
        val denied = send("/excel/public-surveys/$EVENT")
        denied.statusCode() shouldBe 403
        String(denied.body()) shouldNotContain "private-body"

        listOf(null to 401, TestJwt.token("ROLE_USER") to 403, TestJwt.token("ROLE_ADMIN", -10) to 401).forEach { (jwt, status) ->
            send("/excel/public-surveys/$EVENT", jwt).statusCode() shouldBe status
        }
        form.requests.size shouldBe 0
    }

    @Test
    fun `설문 없음은 404, 설문 서비스 장애는 파일 없이 502다`() {
        form.on(ANSWERS, 404, """{"code":"SURVEY_NOT_FOUND"}""")
        send("/excel/public-surveys/$EVENT").statusCode() shouldBe 404

        form.on(ANSWERS, 503, """{"status":503}""")
        val failed = send("/excel/public-surveys/$EVENT")
        failed.statusCode() shouldBe 502
        failed.headers().firstValue("Content-Disposition").isPresent shouldBe false
    }

    private val token = TestJwt.token("ROLE_ADMIN")

    private fun send(
        path: String,
        jwt: String? = token,
    ): HttpResponse<ByteArray> =
        HttpClient.newHttpClient().send(
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$path"))
                .apply { jwt?.let { header("Authorization", "Bearer $it") } }
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    companion object {
        private val expo = StubProvider("expo-secret")
        private val user = StubProvider("user-secret")
        private val application = StubProvider("application-secret")
        private val form = StubProvider("form-secret")

        @JvmStatic
        @AfterAll
        fun close() = listOf(expo, user, application, form).forEach(StubProvider::close)

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
            mapOf("expo" to expo, "user" to user, "application" to application, "form" to form).forEach { (name, provider) ->
                registry.add("clients.$name.url") { provider.url() }
                registry.add("clients.$name.internal-token") { "$name-secret" }
            }
        }
    }
}
