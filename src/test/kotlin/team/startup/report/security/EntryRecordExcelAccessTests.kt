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
private const val RECORDS = "GET /internal/expos/$EVENT/entry-records?page=0&size=2000"

/** 입장 기록 엑셀의 권한 검사와 실제 연결(참여·유저 공급자 스텁). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EntryRecordExcelAccessTests {
    @Value("\${local.server.port}")
    private var port: Int = 0

    @BeforeEach
    fun setup() {
        listOf(user, expo, attendance).forEach {
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
    fun `관리자는 입장 기록 엑셀을 내려받고 한글 파일명이 인코딩된다`() {
        attendance.on(
            RECORDS,
            body =
                mapOf(
                    "page" to 0,
                    "size" to 2000,
                    "totalElements" to 1,
                    "records" to
                        listOf(
                            mapOf(
                                "occurredAt" to "2026-10-19T10:05:12",
                                "eventType" to "ENTER",
                                "subjectType" to "STANDARD",
                                "subjectId" to 7001,
                                "scanner" to "gate-1",
                            ),
                        ),
                    "walkIn" to listOf(mapOf("date" to "2026-10-19", "category" to "TEACHER", "count" to 3)),
                ),
        )
        user.on("POST /internal/standard-participants/names", body = listOf(mapOf("participantId" to 7001, "name" to "홍길동")))

        val response = send("/excel/entry-records/$EVENT")

        response.statusCode() shouldBe 200
        response.headers().firstValue("Content-Disposition").get() shouldContain "filename*=UTF-8''%EC%9E%85%EC%9E%A5%EA%B8%B0%EB%A1%9D_"
        response.headers().firstValue("Cache-Control").get() shouldContain "no-store"
        XSSFWorkbook(ByteArrayInputStream(response.body())).use {
            it.getSheetAt(0).values()[1][3] shouldBe "홍길동"
            it.getSheetAt(1).values()[1] shouldBe listOf("2026-10-19", "교사", "3.0")
        }
        attendance.requests.single().token shouldBe "attendance-secret"
        expo.requests.single().authorization shouldBe "Bearer $token"
    }

    @Test
    fun `기간 파라미터를 참여 서비스에 그대로 넘기고 잘못된 기간은 호출 없이 400이다`() {
        attendance.on(
            "GET /internal/expos/$EVENT/entry-records?from=2026-10-19&to=2026-10-20&page=0&size=2000",
            body = mapOf("page" to 0, "size" to 2000, "totalElements" to 0, "records" to emptyList<Any>()),
        )
        send("/excel/entry-records/$EVENT?from=2026-10-19&to=2026-10-20").statusCode() shouldBe 200
        attendance.requests.size shouldBe 1

        listOf("?from=2026-10-19", "?to=2026-10-19", "?from=2026-10-20&to=2026-10-19", "?from=19-10-2026&to=2026-10-20").forEach {
            send("/excel/entry-records/$EVENT$it").statusCode() shouldBe 400
        }
        attendance.requests.size shouldBe 1
    }

    @Test
    fun `권한 거절과 JWT 실패는 참여·유저 서비스를 조회하지 않는다`() {
        expo.on(ACCESS, 403, "private-body")
        val denied = send("/excel/entry-records/$EVENT")
        denied.statusCode() shouldBe 403
        String(denied.body()) shouldNotContain "private-body"

        listOf(null to 401, TestJwt.token("ROLE_USER") to 403, TestJwt.token("ROLE_ADMIN", -10) to 401).forEach { (jwt, status) ->
            send("/excel/entry-records/$EVENT", jwt).statusCode() shouldBe status
        }
        attendance.requests.size shouldBe 0
        user.requests.size shouldBe 0
    }

    @Test
    fun `참여 서비스 장애는 부분 결과 없이 502다`() {
        attendance.on(RECORDS, 503, """{"status":503}""")
        send("/excel/entry-records/$EVENT").statusCode() shouldBe 502
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
        private val attendance = StubProvider("attendance-secret")

        @JvmStatic
        @AfterAll
        fun close() = listOf(expo, user, application, attendance).forEach(StubProvider::close)

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
            mapOf("expo" to expo, "user" to user, "application" to application, "attendance" to attendance).forEach { (name, provider) ->
                registry.add("clients.$name.url") { provider.url() }
                registry.add("clients.$name.internal-token") { "$name-secret" }
            }
        }
    }
}
