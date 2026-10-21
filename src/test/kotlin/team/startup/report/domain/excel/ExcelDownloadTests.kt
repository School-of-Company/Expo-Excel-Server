package team.startup.report.domain.excel

import io.kotest.matchers.shouldBe
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import team.startup.report.domain.excel.presentation.ExcelDownloadController
import team.startup.report.domain.excel.service.impl.ExcelDownloadService
import team.startup.report.global.client.ApplicationClient
import team.startup.report.global.client.ExpoClient
import team.startup.report.global.client.StubProvider
import team.startup.report.global.client.UserClient
import team.startup.report.global.security.ExcelSecurityConfig
import team.startup.report.support.TestJwt
import team.startup.report.support.values
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID

private val EVENT = UUID.fromString("11111111-1111-1111-1111-111111111111")
private val FIRST = UUID.fromString("22222222-2222-2222-2222-222222222222")
private val SECOND = UUID.fromString("33333333-3333-3333-3333-333333333333")
private val TARGET = "/internal/expos/$EVENT/program-session-applications/report-targets"

@WebMvcTest(ExcelDownloadController::class)
@Import(ExcelSecurityConfig::class, ExcelDownloadService::class, ExcelDownloadTests.ClientConfig::class)
class ExcelDownloadTests {
    @Autowired
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        listOf(application, user, expo).forEach {
            it.routes.clear()
            it.requests.clear()
        }
        expo.on("GET /internal/expo/$EVENT", body = mapOf("title" to "박람회"))
        expo.on(
            "GET /internal/expo/$EVENT/admin-access",
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

    @AfterEach
    fun checkTokens() {
        application.requests.forEach { it.token shouldBe "application-test" }
        user.requests.forEach { it.token shouldBe "user-test" }
        expo.requests.forEach { it.token shouldBe "expo-test" }
    }

    @Test
    fun `선택 신청은 사람 중복을 제거하고 개인 전화번호를 유지한다`() {
        select(listOf(target(FIRST), target(SECOND)))
        expo.on("GET /internal/expo/$EVENT/standard-programs/7", body = mapOf("id" to 7))
        user.on(
            "GET /internal/expos/$EVENT/standard-participants/details?size=500",
            body =
                mapOf(
                    "items" to listOf(person(1) + mapOf("name" to "=SUM(A1)", "phoneNumber" to "010-personal"), person(2)),
                    "nextCursor" to null,
                ),
        )
        val response = send(selected("PROGRAM", listOf(FIRST, SECOND)) + ("programId" to 7))
        response.status shouldBe 200
        response.getHeader("Cache-Control") shouldBe "no-store"
        XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { book ->
            val sheet = book.getSheetAt(0)
            sheet.sheetName shouldBe "프로그램 참가자 정보"
            sheet.lastRowNum shouldBe 1
            sheet.getRow(1).getCell(1).stringCellValue shouldBe "=SUM(A1)"
            sheet
                .getRow(1)
                .getCell(1)
                .cellType.name shouldBe "STRING"
            sheet.getRow(1).getCell(2).stringCellValue shouldBe "010-personal"
        }
    }

    @Test
    fun `프로그램 명단은 소속·직업·신청 경로·신청 시각과 문항 제목별 폼 답변 열을 붙인다`() {
        val school = mapOf("id" to 1, "title" to "학교", "formType" to "SENTENCE", "order" to 1)
        val questions =
            listOf(
                mapOf(
                    "id" to 2,
                    "title" to "학년",
                    "formType" to "DROPDOWN",
                    "order" to 2,
                    "jsonData" to mapOf("0" to "1학년", "1" to "2학년"),
                ),
                school,
            )
        select(
            listOf(
                target(FIRST, answers = mapOf("2" to "1", "1" to "광주초"), questions = questions, route = "ONSITE"),
                // 문항이 바뀐 신청: 학년 문항이 없고 새 문항이 있다
                target(
                    SECOND,
                    participant = 2,
                    answers = mapOf("3" to "010-guardian", "1" to "전남중"),
                    questions = listOf(mapOf("id" to 3, "title" to "보호자 연락처", "formType" to "SENTENCE", "order" to 0), school),
                    route = "PRE_REGISTRATION",
                ),
            ),
        )
        expo.on("GET /internal/expo/$EVENT/standard-programs/7", body = mapOf("id" to 7))
        user.on(
            "GET /internal/expos/$EVENT/standard-participants/details?size=500",
            body =
                mapOf(
                    "items" to
                        listOf(
                            person(1) + mapOf("occupation" to "ELEMENTARY_STUDENT", "school" to "등록초"),
                            person(2),
                            person(3),
                        ),
                    "nextCursor" to null,
                ),
        )
        val response = send(selected("PROGRAM", listOf(FIRST, SECOND)) + ("programId" to 7))
        response.status shouldBe 200
        sheet(response) shouldBe
            listOf(
                listOf("순위", "이름", "전화번호", "개인정보 동의 여부", "소속", "직업", "신청 경로", "신청 시각", "학교", "학년", "보호자 연락처", "학번", "서명", "비고"),
                listOf("1.0", "참가자", "010", "동의", "등록초", "초등학생", "현장", "2026-01-01 09:00:00", "광주초", "2학년", null, "", "", ""),
                listOf("2.0", "참가자", "010", "동의", null, null, "사전", "2026-01-01 09:00:00", "전남중", null, "010-guardian", "", "", ""),
            )
    }

    @Test
    fun `개발 서버 골든벨 형태의 문항 ID 답변을 제출 당시 제목과 보기 문구로 출력한다`() {
        // 실제 dev 원천 모양: order 없는 배열, 숫자 ID, DROPDOWN 보기 키, CHECKBOX boolean. 수정 전에는 319/320/321과 4가 그대로 나왔다
        val questions =
            listOf(
                mapOf(
                    "id" to 319,
                    "title" to "초등학교 학년",
                    "formType" to "DROPDOWN",
                    "dynamicFormType" to "DEFAULT",
                    "requiredStatus" to true,
                    "jsonData" to (1..6).associate { "$it" to "${it}학년" },
                    "otherJson" to null,
                ),
                mapOf(
                    "id" to 320,
                    "title" to "참가 학교",
                    "formType" to "SENTENCE",
                    "jsonData" to emptyMap<String, Any>(),
                    "otherJson" to null,
                ),
                mapOf(
                    "id" to 321,
                    "title" to "확인했습니다",
                    "formType" to "CHECKBOX",
                    "jsonData" to emptyMap<String, Any>(),
                    "otherJson" to null,
                ),
            )
        select(
            listOf(
                target(
                    FIRST,
                    answers = mapOf("319" to "4", "320" to "QA초등학교", "321" to true),
                    questions = questions,
                    route = "PRE_REGISTRATION",
                ),
                target(
                    SECOND,
                    participant = 2,
                    answers = mapOf("319" to "4", "320" to null, "321" to false),
                    questions = questions,
                    route = "ONSITE",
                ),
                target(UUID(0, 3), participant = 3, answers = mapOf("319" to "4"), questions = questions, route = "ONSITE"),
            ),
        )
        expo.on("GET /internal/expo/$EVENT/standard-programs/7", body = mapOf("id" to 7))
        user.on(
            "GET /internal/expos/$EVENT/standard-participants/details?size=500",
            body = mapOf("items" to listOf(person(1), person(2), person(3)), "nextCursor" to null),
        )
        val rows = sheet(send(selected("PROGRAM", listOf(FIRST, SECOND, UUID(0, 3))) + ("programId" to 7)))
        rows[0].subList(8, 11) shouldBe listOf("초등학교 학년", "참가 학교", "확인했습니다")
        rows.drop(1).map { it.subList(8, 11) } shouldBe
            listOf(listOf("4학년", "QA초등학교", "true"), listOf("4학년", null, "false"), listOf("4학년", null, null))
    }

    @Test
    fun `필터 전체는 고정 스냅샷 끝까지 읽고 제출 당시 답변을 기존 열에만 반영한다`() {
        application.on("POST $TARGET", body = snapshot(listOf(target(FIRST)), "next"))
        application.on("GET $TARGET?cursor=next", body = snapshot(listOf(target(SECOND, participant = 2)), null))
        user.on(
            "GET /internal/expos/$EVENT/standard-participants/details?size=500",
            body =
                mapOf(
                    "items" to listOf(person(1), person(2)),
                    "nextCursor" to null,
                ),
        )
        val response =
            send(
                mapOf(
                    "template" to "STANDARD",
                    "scope" to "FILTER",
                    "filter" to mapOf("status" to "CONFIRMED", "search" to "회차"),
                ),
            )
        response.status shouldBe 200
        XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { book ->
            val sheet = book.getSheetAt(0)
            sheet.lastRowNum shouldBe 2
            sheet.getRow(0).getCell(4).stringCellValue shouldBe "학교명"
            sheet.getRow(1).getCell(4).stringCellValue shouldBe "프로그램학교"
            sheet.getRow(0).lastCellNum.toInt() shouldBe 5
        }
        application.requests.size shouldBe 2
        val filter = StubProvider.JSON.readTree(application.requests.first().body).path("filter")
        filter.path("search").asString() shouldBe "회차"
        filter.has("q") shouldBe false
    }

    @Test
    fun `개인 출석부 전체 범위는 본인 이름으로 스냅샷 대상을 줄인다`() {
        user.on("GET /internal/trainees/10/details", body = trainee() + ("expoId" to EVENT))
        user.on("POST /internal/standard-participants/names", body = listOf(mapOf("participantId" to 1, "name" to " 교원 ")))
        val other =
            target(SECOND, type = "TRAINING", program = 8).let {
                it +
                    ("application" to (it["application"] as Map<*, *>) + ("traineeId" to 11))
            }
        application.on("POST $TARGET", body = snapshot(listOf(target(FIRST, type = "TRAINING"), other), null))
        val response = send(mapOf("template" to "ATTENDANCE", "scope" to "FILTER", "traineeId" to 10))
        response.status shouldBe 200
        val filter = StubProvider.JSON.readTree(application.requests.single().body).path("filter")
        filter.path("q").asString() shouldBe "교원"
        filter.path("programType").asString() shouldBe "TRAINING"
    }

    @Test
    fun `연수 명단은 신규 원장 회차 스냅샷을 쓰고 등록 답변을 우선한다`() {
        select(listOf(target(FIRST, type = "TRAINING")))
        user.on("GET /internal/expos/$EVENT/trainees/details?size=500", body = mapOf("items" to listOf(trainee()), "nextCursor" to null))
        val response = send(selected("TRAINEE", listOf(FIRST)))
        response.status shouldBe 200
        XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { book ->
            val sheet = book.getSheetAt(0)
            sheet.getRow(1).getCell(1).stringCellValue shouldBe ""
            sheet.getRow(1).getCell(2).stringCellValue shouldBe ""
            sheet.getRow(1).getCell(4).stringCellValue shouldBe "등록학교"
            sheet.getRow(1).getCell(5).stringCellValue shouldBe "회차"
        }
        application.requests.none { it.uri.contains("/trainees") } shouldBe true
    }

    @Test
    fun `개인 출석부는 같은 행사 본인 신청만 사용한다`() {
        select(listOf(target(FIRST, type = "TRAINING")))
        user.on("GET /internal/trainees/10/details", body = trainee() + ("expoId" to EVENT))
        val response = send(selected("ATTENDANCE", listOf(FIRST)) + ("traineeId" to 10))
        response.status shouldBe 200
        XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { it.getSheetAt(0).sheetName shouldBe "출석부" }
        user.on("GET /internal/trainees/10/details", body = trainee() + ("expoId" to UUID.randomUUID()))
        send(selected("ATTENDANCE", listOf(FIRST)) + ("traineeId" to 10)).status shouldBe 502
    }

    @Test
    fun `회차 혼합과 대기 취소 신청을 출석용 명단으로 출력하지 않는다`() {
        select(listOf(target(FIRST), target(SECOND, program = 8)))
        send(selected("PROGRAM", listOf(FIRST, SECOND)) + ("programId" to 7)).status shouldBe 400
        listOf("WAITING", "CANCELLED").forEach { status ->
            select(listOf(target(FIRST, status = status)))
            send(selected("PROGRAM", listOf(FIRST)) + ("programId" to 7)).status shouldBe 400
        }
    }

    @Test
    fun `누락 중복 타 행사와 등록 원천 불일치는 파일을 생성하지 않는다`() {
        val bodies =
            listOf(
                mapOf("items" to emptyList<Any>(), "totalElements" to 1),
                mapOf("items" to listOf(target(SECOND)), "totalElements" to 1),
                mapOf("items" to listOf(target(FIRST), target(FIRST)), "totalElements" to 1),
                mapOf("items" to listOf(target(FIRST) + ("expoId" to UUID.randomUUID())), "totalElements" to 1),
                mapOf(
                    "items" to listOf(target(FIRST) + ("registrationSource" to mapOf("expoId" to EVENT, "participantId" to 2))),
                    "totalElements" to 1,
                ),
            )
        bodies.forEach { body ->
            application.on("POST $TARGET/selection", body = body)
            send(selected("STANDARD", listOf(FIRST))).status shouldBe 502
        }
        user.requests.size shouldBe 0
    }

    @Test
    fun `스냅샷 변경 반복 커서 중복 항목과 건수 오류는 502다`() {
        application.on("POST $TARGET", body = snapshot(listOf(target(FIRST)), "next"))
        val invalid =
            listOf(
                snapshot(listOf(target(SECOND)), null) + ("snapshotId" to UUID.randomUUID()),
                snapshot(listOf(target(SECOND)), null) + ("snapshotAt" to Instant.EPOCH),
                snapshot(listOf(target(SECOND)), null) + ("expiresAt" to Instant.EPOCH),
                snapshot(listOf(target(SECOND)), null, 3),
                snapshot(listOf(target(FIRST)), null),
                snapshot(emptyList(), null),
                snapshot(listOf(target(SECOND)), "next"),
            )
        invalid.forEach {
            application.on("GET $TARGET?cursor=next", body = it)
            send(mapOf("template" to "STANDARD", "scope" to "FILTER")).status shouldBe 502
        }
        application.on("POST $TARGET", body = snapshot(emptyList(), null, -1))
        send(mapOf("template" to "STANDARD", "scope" to "FILTER")).status shouldBe 502
    }

    @Test
    fun `Report 자체 전체 상한 초과는 413이며 등록 원천을 조회하지 않는다`() {
        application.on("POST $TARGET", body = snapshot(listOf(target(FIRST)), "next", 10_001))
        val response = send(mapOf("template" to "STANDARD", "scope" to "FILTER"))
        response.status shouldBe 413
        response.getHeader(HttpHeaders.CONTENT_DISPOSITION) shouldBe null
        application.requests.size shouldBe 1
        user.requests.size shouldBe 0
    }

    @Test
    fun `유효한 스냅샷의 시간 만료만 410이며 응답 계약 오류는 502다`() {
        val expired = snapshot(listOf(target(FIRST)), null, 1) + ("expiresAt" to Instant.parse("2026-01-01T00:15:00Z"))
        application.on("POST $TARGET", body = expired)
        val response = send(mapOf("template" to "STANDARD", "scope" to "FILTER"))
        response.status shouldBe 410
        response.getHeader(HttpHeaders.CONTENT_DISPOSITION) shouldBe null
        val invalid =
            listOf(
                expired + ("totalElements" to 2),
                expired + ("items" to listOf(target(FIRST) + ("expoId" to UUID.randomUUID()))),
            )
        invalid.forEach {
            application.on("POST $TARGET", body = it)
            send(mapOf("template" to "STANDARD", "scope" to "FILTER")).status shouldBe 502
        }
        user.requests.size shouldBe 0
    }

    @Test
    fun `Application 스냅샷 생성의 상한 422만 Report 413으로 변환한다`() {
        application.on("POST $TARGET", 422, "private-provider-limit")
        val response = send(mapOf("template" to "STANDARD", "scope" to "FILTER"))
        response.status shouldBe 413
        response.contentAsString.contains("private-provider-limit") shouldBe false
        application.on("POST $TARGET/selection", 422, "private-invalid-selection")
        send(selected("STANDARD", listOf(FIRST))).status shouldBe 502
        application.on("POST $TARGET", body = snapshot(listOf(target(FIRST)), "next"))
        application.on("GET $TARGET?cursor=next", 422, "private-invalid-page")
        send(mapOf("template" to "STANDARD", "scope" to "FILTER")).status shouldBe 502
        user.requests.size shouldBe 0
    }

    @Test
    fun `요청 검증은 공급자 호출 전에 수행한다`() {
        val invalid =
            listOf(
                selected("STANDARD", emptyList()),
                selected("STANDARD", listOf(FIRST, FIRST)),
                selected("STANDARD", listOf(FIRST)) + ("filter" to emptyMap<String, Any>()),
                mapOf("template" to "STANDARD", "scope" to "FILTER", "page" to 1),
                mapOf("template" to "STANDARD", "scope" to "FILTER", "filter" to mapOf("size" to 20)),
                mapOf("template" to "PROGRAM", "scope" to "FILTER", "programId" to 7),
                mapOf("template" to "STANDARD", "scope" to "FILTER", "filter" to mapOf("search" to " ")),
                mapOf("scope" to "FILTER"),
                mapOf("template" to "STANDARD"),
            )
        invalid.forEach { send(it).status shouldBe 400 }
        listOf(application, user).sumOf { it.requests.size } shouldBe 0
        expo.requests.all { it.uri.endsWith("/admin-access") } shouldBe true
    }

    @Test
    fun `공급자 장애 본문과 토큰을 응답에 노출하지 않는다`() {
        application.on("POST $TARGET/selection", 503, "secret-provider-body")
        val response = send(selected("STANDARD", listOf(FIRST)))
        response.status shouldBe 502
        response.contentAsString.contains("secret-provider-body") shouldBe false
        response.contentAsString.contains("application-test") shouldBe false
    }

    @Test
    fun `관리자 JWT만 새 다운로드 경로에 접근할 수 있다`() {
        val request = selected("STANDARD", listOf(FIRST))
        send(request, null).status shouldBe 401
        send(request, TestJwt.token("ROLE_USER")).status shouldBe 403
        send(request, TestJwt.token("ROLE_ADMIN", -10)).status shouldBe 401
        application.requests.size shouldBe 0
    }

    @ParameterizedTest
    @ValueSource(ints = [1001, 10_000])
    fun `상한 이내 전체 신청은 페이지 500경계를 넘어 읽고 사람 한 명으로 중복 제거한다`(total: Int) {
        val pages = (1L..total.toLong()).map { target(UUID(0, it)) }.chunked(500)
        pages.forEachIndexed { index, items ->
            val route = if (index == 0) "POST $TARGET" else "GET $TARGET?cursor=page$index"
            val next = if (index < pages.lastIndex) "page${index + 1}" else null
            application.on(route, body = snapshot(items, next, total))
        }
        user.on(
            "GET /internal/expos/$EVENT/standard-participants/details?size=500",
            body =
                mapOf(
                    "items" to listOf(person(1)),
                    "nextCursor" to null,
                ),
        )
        val response = send(mapOf("template" to "STANDARD", "scope" to "FILTER"))
        response.status shouldBe 200
        application.requests.size shouldBe pages.size
        XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { it.getSheetAt(0).lastRowNum shouldBe 1 }
    }

    @Test
    fun `공급자 검증 만료 상한 오류는 상태를 보존한다`() {
        listOf(400, 404, 410, 413).forEach { status ->
            application.on("POST $TARGET/selection", status, "private-error")
            val response = send(selected("STANDARD", listOf(FIRST)))
            response.status shouldBe status
            response.contentAsString.contains("private-error") shouldBe false
            application.on("POST $TARGET", status, "private-error")
            send(mapOf("template" to "STANDARD", "scope" to "FILTER")).status shouldBe status
            application.on("POST $TARGET", body = snapshot(listOf(target(FIRST)), "next"))
            application.on("GET $TARGET?cursor=next", status, "private-error")
            send(mapOf("template" to "STANDARD", "scope" to "FILTER")).status shouldBe status
        }
    }

    @Test
    fun `명단은 선택 대기 취소 신청을 허용하고 단일 사람으로 출력한다`() {
        select(listOf(target(FIRST, status = "WAITING"), target(SECOND, status = "CANCELLED")))
        user.on(
            "GET /internal/expos/$EVENT/standard-participants/details?size=500",
            body =
                mapOf(
                    "items" to listOf(person(1)),
                    "nextCursor" to null,
                ),
        )
        val response = send(selected("STANDARD", listOf(FIRST, SECOND)))
        response.status shouldBe 200
        XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { it.getSheetAt(0).lastRowNum shouldBe 1 }
    }

    @Test
    fun `일반 명단 필터와 선택은 GET과 같은 자기 값을 쓰고 고른 사람만 ID 순으로 한 번씩 출력한다`() {
        val party = "GET /internal/expos/$EVENT/standard-participants/details?size=500"
        user.on(party, body = mapOf("items" to party(), "nextCursor" to null))

        application.on("POST $TARGET", body = snapshot((1L..5L).map { target(UUID(0, it), participant = it) }, null, total = 5))
        sheet(send(mapOf("template" to "STANDARD", "scope" to "FILTER"))) shouldBe PARTY_ROWS

        // 동행자만 고르면 대표자는 열 제목에만 쓰이고 행으로 나오지 않는다. 같은 사람의 신청 두 건은 한 행이다.
        select(listOf(target(FIRST, participant = 5), target(SECOND, participant = 3), target(UUID(0, 9), participant = 5)))
        sheet(send(selected("STANDARD", listOf(FIRST, SECOND, UUID(0, 9))))) shouldBe listOf(PARTY_ROWS[0], PARTY_ROWS[3], PARTY_ROWS[5])
    }

    private fun sheet(response: MockHttpServletResponse): List<List<String?>> {
        response.status shouldBe 200
        return XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { it.getSheetAt(0).values() }
    }

    private fun send(
        body: Any,
        token: String? = TestJwt.token("ROLE_ADMIN"),
    ) = mvc
        .perform(
            post("/excel/expos/$EVENT/downloads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(StubProvider.JSON.writeValueAsString(body))
                .apply { token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") } },
        ).andReturn()
        .response

    private fun selected(
        template: String,
        ids: List<UUID>,
    ) = mapOf("template" to template, "scope" to "SELECTED", "applicationIds" to ids)

    private fun select(items: List<Any>) =
        application.on(
            "POST $TARGET/selection",
            body =
                mapOf(
                    "items" to items,
                    "totalElements" to items.size,
                ),
        )

    private fun target(
        id: UUID,
        participant: Long = 1,
        type: String = "STANDARD",
        program: Long = 7,
        status: String = "CONFIRMED",
        // 프로그램 답변은 Application ProgramFormAdapter가 검증한 문항 ID 키다
        answers: Map<String, Any?> = mapOf("501" to "프로그램학교", "502" to "추가 금지"),
        questions: List<Any> =
            listOf(
                mapOf("id" to 501, "title" to "학교명", "formType" to "SENTENCE"),
                mapOf("id" to 502, "title" to "새열", "formType" to "SENTENCE"),
            ),
        route: String? = null,
    ): Map<String, Any> =
        mapOf(
            "expoId" to EVENT,
            "registrationSource" to mapOf("expoId" to EVENT, "participantId" to participant),
            "application" to
                mapOf(
                    "applicationId" to id,
                    "participantId" to participant,
                    "traineeId" to 10,
                    "programType" to type,
                    "programId" to program,
                    "groupId" to FIRST,
                    "status" to status,
                    "createdAt" to Instant.parse("2026-01-01T00:00:00Z"),
                    "formId" to FIRST,
                    "formVersion" to 1,
                    "definition" to
                        mapOf(
                            "expoId" to EVENT,
                            "id" to program,
                            "programType" to type,
                            "title" to "회차",
                            "category" to "ESSENTIAL",
                            "startedAt" to "2026-01-01T09:00",
                            "endedAt" to "2026-01-01T10:00",
                        ),
                    "questions" to questions,
                    "answers" to answers,
                    "admissionRoute" to route,
                ),
        )

    private fun snapshot(
        items: List<Any>,
        next: String?,
        total: Int = 2,
    ) = mapOf(
        "snapshotId" to FIRST,
        "snapshotAt" to Instant.parse("2026-01-01T00:00:00Z"),
        "expiresAt" to Instant.parse("2099-01-01T00:00:00Z"),
        "totalElements" to total,
        "items" to items,
        "nextCursor" to next,
    )

    private fun person(id: Long) =
        mapOf(
            "participantId" to id,
            "name" to "참가자",
            "phoneNumber" to "010",
            "personalInformationStatus" to true,
            "applicationType" to "PRE",
            "information" to mapOf("answers" to mapOf("학교명" to null)),
            "surveyAnswer" to null,
            "region" to null,
            "school" to null,
        )

    private fun trainee() =
        mapOf(
            "traineeId" to 10,
            "participantId" to 1,
            "name" to "교원",
            "trainingId" to null,
            "phoneNumber" to null,
            "applicationType" to "PRE",
            "information" to mapOf("answers" to emptyMap<String, Any>()),
            "registration" to mapOf("answers" to mapOf("학교명" to "등록학교")),
        )

    @TestConfiguration
    class ClientConfig {
        @Bean
        fun applicationClient() = ApplicationClient(application.client())

        @Bean
        fun userClient() = UserClient(user.client())

        @Bean
        fun expoClient() = ExpoClient(expo.client())
    }

    companion object {
        private val application = StubProvider("application-test")
        private val user = StubProvider("user-test")
        private val expo = StubProvider("expo-test")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
        }
    }
}
