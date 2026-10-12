package team.startup.report.security

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import team.startup.report.global.client.StubProvider
import team.startup.report.support.TestJwt
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private const val EVENT = "11111111-1111-1111-1111-111111111111"
private const val ACCESS = "GET /internal/expo/$EVENT/admin-access"

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExcelAccessTests {
    @Value("\${local.server.port}")
    private var port: Int = 0

    @BeforeEach
    fun setup() {
        listOf(user, expo, application).forEach {
            it.routes.clear()
            it.requests.clear()
        }
        expo.on(ACCESS, body = access())
        user.on("GET /internal/trainees/10/details", body = trainee())
    }

    @Test
    fun `5종 다운로드는 권한 거절 시 목록 신청 파일을 조회하지 않는다`() {
        expo.on(ACCESS, 403, "private-body")
        paths().forEach { (path, body) ->
            listOf(user, expo, application).forEach { it.requests.clear() }
            val response = send(path, body)
            response.statusCode() shouldBe 403
            response.body().contains("private-body") shouldBe false
            application.requests.size shouldBe 0
            expo.requests.map { it.uri } shouldBe listOf("/internal/expo/$EVENT/admin-access")
            user.requests.map { it.uri } shouldBe if (path.contains("/trainee/")) listOf("/internal/trainees/10/details") else emptyList()
        }
    }

    @Test
    fun `JWT 실패와 위조 Gateway 헤더는 공급자 호출 전에 거절한다`() {
        val tokens =
            listOf(
                null to 401,
                TestJwt.token("ROLE_USER") to 403,
                TestJwt.token("ROLE_ADMIN", -10) to 401,
                TestJwt.token("ROLE_ADMIN", signedByOtherKey = true) to 401,
            )
        paths().forEach { (path, body) -> tokens.forEach { (token, status) -> send(path, body, token).statusCode() shouldBe status } }
        listOf(user, expo, application).sumOf { it.requests.size } shouldBe 0
    }

    @Test
    fun `응답 행사 관리자 정책 권한 또는 숫자 타입이 다르면 우회하지 않는다`() {
        val invalid =
            listOf(
                access() + ("expoId" to "22222222-2222-2222-2222-222222222222"),
                access() + ("adminId" to 2),
                access() + ("adminId" to "1"),
                access() + ("permission" to "APPLICATION_CANCEL"),
                access() + ("scope" to "EXPO_ADMIN"),
                access() + ("policyVersion" to "OTHER"),
                access() - "permission",
            )
        invalid.forEach { payload ->
            expo.on(ACCESS, body = payload)
            send("/excel/standard/$EVENT").statusCode() shouldBe 502
        }
        user.requests.size shouldBe 0
        application.requests.size shouldBe 0
    }

    @Test
    fun `공급자 상태와 잘못된 JSON을 안전하게 처리한다`() {
        listOf(401, 403, 404, 500, 503).forEach { status ->
            expo.on(ACCESS, status, "private-body")
            val response = send("/excel/standard/$EVENT")
            response.statusCode() shouldBe if (status in listOf(401, 403, 404)) status else 502
            response.body().contains("private-body") shouldBe false
            response.body().contains("expo-secret") shouldBe false
        }
        expo.on(ACCESS, body = "not-json")
        send("/excel/standard/$EVENT").statusCode() shouldBe 502
        user.requests.size shouldBe 0
    }

    @Test
    fun `허용된 명단 다운로드는 원본 Bearer와 내부 토큰을 함께 전송한다`() {
        allowStandard()
        val token = TestJwt.token("ROLE_ADMIN")
        send("/excel/standard/$EVENT", token = token).statusCode() shouldBe 200
        expo.requests.first().authorization shouldBe "Bearer $token"
        expo.requests.first().token shouldBe "expo-secret"
        user.requests.forEach { it.authorization shouldBe null }
    }

    @Test
    fun `선택과 필터 다운로드도 동일한 권한 검사를 거친다`() {
        allowStandard()
        val target =
            mapOf(
                "expoId" to EVENT,
                "registrationSource" to mapOf("expoId" to EVENT, "participantId" to 1),
                "application" to
                    mapOf(
                        "applicationId" to EVENT,
                        "participantId" to 1,
                        "traineeId" to null,
                        "programType" to "STANDARD",
                        "programId" to 7,
                        "groupId" to EVENT,
                        "status" to "CONFIRMED",
                        "createdAt" to "2026-01-01T00:00:00Z",
                        "formId" to EVENT,
                        "formVersion" to 1,
                        "definition" to mapOf("expoId" to EVENT),
                        "questions" to emptyList<Any>(),
                        "answers" to emptyMap<String, Any>(),
                    ),
            )
        application.on(
            "POST /internal/expos/$EVENT/program-session-applications/report-targets/selection",
            body =
                mapOf("items" to listOf(target), "totalElements" to 1),
        )
        application.on(
            "POST /internal/expos/$EVENT/program-session-applications/report-targets",
            body =
                mapOf(
                    "items" to listOf(target),
                    "totalElements" to 1,
                    "snapshotId" to EVENT,
                    "snapshotAt" to "2026-01-01T00:00:00Z",
                    "expiresAt" to "2099-01-01T00:00:00Z",
                    "nextCursor" to null,
                ),
        )
        val token = TestJwt.token("ROLE_ADMIN")
        listOf(
            """{"template":"STANDARD","scope":"SELECTED","applicationIds":["$EVENT"]}""",
            """{"template":"STANDARD","scope":"FILTER"}""",
        ).forEach { body ->
            send("/excel/expos/$EVENT/downloads", body, token).statusCode() shouldBe 200
        }
        expo.requests.filter { it.uri.endsWith("/admin-access") }.forEach {
            it.authorization shouldBe "Bearer $token"
            it.token shouldBe "expo-secret"
        }
        application.requests.forEach { it.authorization shouldBe null }
    }

    @Test
    fun `다른 행사 권한 거절을 같은 행사 허용으로 재사용하지 않는다`() {
        allowStandard()
        send("/excel/standard/$EVENT").statusCode() shouldBe 200
        val other = "22222222-2222-2222-2222-222222222222"
        expo.on("GET /internal/expo/$other/admin-access", 403, "denied")
        send("/excel/standard/$other").statusCode() shouldBe 403
        user.requests.size shouldBe 1
    }

    @Test
    fun `개인 출석부는 실제 연수자 행사로 검증하고 같은 원천을 재사용한다`() {
        expo.on("GET /internal/expo/$EVENT", body = mapOf("title" to "행사"))
        application.on(
            "POST /internal/training-program-applications/trainees",
            body =
                listOf(
                    mapOf(
                        "applicationId" to 1,
                        "traineeId" to 10,
                        "trainingProgramId" to 7,
                    ),
                ),
        )
        expo.on(
            "POST /internal/expo/$EVENT/training-programs/batch",
            body =
                listOf(
                    mapOf(
                        "id" to 7,
                        "title" to "강연",
                        "category" to "ESSENTIAL",
                        "startedAt" to "2026-01-01T09:00",
                        "endedAt" to "2026-01-01T10:00",
                    ),
                ),
        )
        send("/excel/trainee/10").statusCode() shouldBe 200
        user.requests.size shouldBe 1
        user.on("GET /internal/trainees/10/details", body = trainee() + ("traineeId" to 20))
        send("/excel/trainee/10").statusCode() shouldBe 502
    }

    private fun send(
        path: String,
        body: String? = null,
        token: String? = TestJwt.token("ROLE_ADMIN"),
    ): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$path"))
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .apply {
                    if (body !=
                        null
                    ) {
                        header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
                    } else {
                        GET()
                    }
                }.build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun paths() =
        listOf(
            "/excel/$EVENT" to null,
            "/excel/standard/$EVENT" to null,
            "/excel/program/$EVENT?programId=7" to null,
            "/excel/trainee/10" to null,
            "/excel/expos/$EVENT/downloads" to """{"template":"STANDARD","scope":"FILTER"}""",
        )

    private fun access(): Map<String, Any> =
        mapOf(
            "expoId" to EVENT,
            "adminId" to 1,
            "permission" to "APPLICATION_READ",
            "policyVersion" to "GLOBAL_ADMIN_APPLICATION_READ_V1",
            "scope" to "GLOBAL_ADMIN",
        )

    private fun trainee(): Map<String, Any> =
        mapOf(
            "traineeId" to 10,
            "expoId" to EVENT,
            "name" to "예시",
            "information" to mapOf("answers" to emptyMap<String, Any>()),
        )

    private fun allowStandard() {
        expo.on("GET /internal/expo/$EVENT", body = mapOf("title" to "행사"))
        val person =
            mapOf(
                "participantId" to 1,
                "name" to "예시",
                "phoneNumber" to null,
                "personalInformationStatus" to true,
                "applicationType" to "PRE",
                "information" to mapOf("answers" to emptyMap<String, Any>()),
                "surveyAnswer" to null,
            )
        user.on(
            "GET /internal/expos/$EVENT/standard-participants/details?size=500",
            body =
                mapOf(
                    "items" to listOf(person),
                    "nextCursor" to null,
                ),
        )
    }

    companion object {
        private val expo = StubProvider("expo-secret")
        private val user = StubProvider("user-secret")
        private val application = StubProvider("application-secret")

        @JvmStatic
        @AfterAll
        fun close() = listOf(expo, user, application).forEach(StubProvider::close)

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
            mapOf("expo" to expo, "user" to user, "application" to application).forEach { (name, provider) ->
                registry.add("clients.$name.url") { provider.url() }
                registry.add("clients.$name.internal-token") { "$name-secret" }
            }
        }
    }
}
