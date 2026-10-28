package team.startup.report.domain.excel

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

private val EVENT = UUID.fromString("11111111-1111-1111-1111-111111111111")
private val CANCELLED = UUID.fromString("a0000000-0000-0000-0000-000000000001")
private val CURRENT = UUID.fromString("a0000000-0000-0000-0000-000000000002")
private val COMPANION = UUID.fromString("a0000000-0000-0000-0000-000000000003")
private val TRAINING_A = UUID.fromString("a0000000-0000-0000-0000-000000000004")
private val TRAINING_B = UUID.fromString("a0000000-0000-0000-0000-000000000005")
private val TARGET = "/internal/expos/$EVENT/program-session-applications/report-targets"

// 합성 fixture: 실제 dev 골든벨(회차 73) 모양(숫자 문항 ID·DROPDOWN·CHECKBOX)과 취소/현재 확정 공존을 흉내 낸다. 실제 번호·토큰 없음.
private val QUESTIONS =
    listOf(
        mapOf("id" to 319, "title" to "초등학교 학년", "formType" to "DROPDOWN", "jsonData" to mapOf("4" to "4학년", "5" to "5학년")),
        mapOf("id" to 320, "title" to "참가 학교", "formType" to "SENTENCE"),
        mapOf("id" to 321, "title" to "확인", "formType" to "CHECKBOX"),
    )

@WebMvcTest(ExcelDownloadController::class)
@Import(ExcelSecurityConfig::class, ExcelDownloadService::class, ApplicationLedgerTests.ClientConfig::class)
class ApplicationLedgerTests {
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
        user.on(
            "GET /internal/expos/$EVENT/standard-participants/details?size=500",
            body =
                mapOf(
                    "items" to
                        listOf(
                            person(1, phone = "010-0000-0001", notification = "010-0000-0001", representative = null),
                            // 동행자: 본인 번호 없음, 알림 번호는 대표자 번호
                            person(2, phone = null, notification = "010-0000-0001", representative = 1),
                            person(3, phone = "010-0000-0003", notification = "010-0000-0003", representative = null),
                        ),
                    "nextCursor" to null,
                ),
        )
        user.on(
            "GET /internal/expos/$EVENT/trainees/details?size=500",
            body =
                mapOf(
                    "items" to
                        listOf(
                            mapOf(
                                "traineeId" to 10,
                                "participantId" to 1,
                                // 연수자 프로필 이름은 참가자 이름과 다를 수 있고 덮어쓰지 않는다
                                "name" to "연수자프로필",
                                "trainingId" to null,
                                "phoneNumber" to null,
                                "applicationType" to "PRE",
                                "information" to mapOf("answers" to emptyMap<String, Any>()),
                            ),
                        ),
                    "nextCursor" to null,
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
    fun `같은 사람의 취소 신청과 현재 확정 신청, 여러 연수 신청을 각각 한 행으로 출력하고 이력을 모두 보존한다`() {
        select(
            listOf(
                target(
                    CANCELLED,
                    receipt = 5,
                    status = "CANCELLED",
                    route = "PRE_REGISTRATION",
                    history = listOf(history(1, "CONFIRMED", "2026-10-01T00:00:00Z"), history(2, "CANCELLED", "2026-10-02T00:00:00Z")),
                    answers = mapOf("319" to "4", "320" to "광주초", "321" to false),
                ),
                target(
                    CURRENT,
                    receipt = 9,
                    route = "ONSITE",
                    // 대기에서 승급된 현재 확정 신청. 답변 null·스냅샷에 없는 문항 999는 열로 만들지 않는다
                    history = listOf(history(3, "WAITING", "2026-10-03T00:00:00Z"), history(4, "CONFIRMED", "2026-10-04T00:00:00Z")),
                    answers = mapOf("319" to "5", "320" to null, "321" to true, "999" to "x"),
                ),
                // 동행자, 상태 기록이 없는 과거 취소 신청
                target(
                    COMPANION,
                    participant = 2,
                    receipt = 6,
                    status = "CANCELLED",
                    history = emptyList(),
                    answers =
                        mapOf("320" to "줄\n바꿈"),
                ),
                target(TRAINING_A, type = "TRAINING", program = 81, trainee = 10, receipt = 7, answers = emptyMap<String, Any>()),
                target(TRAINING_B, type = "TRAINING", program = 82, trainee = 10, receipt = 8, answers = emptyMap<String, Any>()),
            ),
        )
        // 고른 순서로 출력한다
        val response = send(selected(listOf(CURRENT, CANCELLED, COMPANION, TRAINING_A, TRAINING_B)))
        response.status shouldBe 200
        response.getHeader("Cache-Control") shouldBe "no-store"
        response.getHeader(HttpHeaders.CONTENT_DISPOSITION)!! shouldStartWith
            "attachment; filename*=UTF-8''%EC%8B%A0%EC%B2%AD%EC%9B%90%EC%9E%A5_"
        save(response)
        XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { book ->
            book.getSheetAt(0).sheetName shouldBe "신청 원장"
            val ledger = book.getSheetAt(0).values()
            ledger.size shouldBe 6
            ledger[0] shouldBe
                listOf(
                    "원장 유형",
                    "신청 ID",
                    "접수번호",
                    "참가자 ID",
                    "연수자 ID",
                    "참가자 이름",
                    "연수자 이름",
                    "직업",
                    "소속",
                    "본인 지역",
                    "대표/동행",
                    "대표자 ID",
                    "본인 연락처",
                    "알림 연락처",
                    "회차 ID",
                    "회차 제목",
                    "운영 시작",
                    "운영 종료",
                    "장소",
                    "신청 경로",
                    "상태",
                    "신청 시각",
                    "최근 취소 시각",
                    "최근 승급 시각",
                    "이력 비고",
                    "미복원 답변 수",
                    "초등학교 학년",
                    "참가 학교",
                    "확인",
                )
            ledger[1] shouldBe
                listOf(
                    "프로그램 신청",
                    "$CURRENT",
                    "9",
                    "1",
                    null,
                    "참가자1",
                    null,
                    "교원",
                    "소마초",
                    "광주",
                    "대표",
                    null,
                    "010-0000-0001",
                    "010-0000-0001",
                    "73",
                    "골든벨",
                    "2026-10-31 09:00",
                    "2026-10-31 10:00",
                    "강당",
                    "현장",
                    "확정",
                    "2026-10-01 09:00:00",
                    null,
                    "2026-10-04 09:00:00",
                    null,
                    "1",
                    "5학년",
                    null,
                    "true",
                )
            ledger[2] shouldBe
                listOf(
                    "프로그램 신청",
                    "$CANCELLED",
                    "5",
                    "1",
                    null,
                    "참가자1",
                    null,
                    "교원",
                    "소마초",
                    "광주",
                    "대표",
                    null,
                    "010-0000-0001",
                    "010-0000-0001",
                    "73",
                    "골든벨",
                    "2026-10-31 09:00",
                    "2026-10-31 10:00",
                    "강당",
                    "사전",
                    "취소",
                    "2026-10-01 09:00:00",
                    "2026-10-02 09:00:00",
                    null,
                    null,
                    null,
                    "4학년",
                    "광주초",
                    "false",
                )
            // 동행자: 본인 번호 빈칸, 알림 번호는 대표자 번호, 이력 미기록은 빈 시각 + 비고
            ledger[3].subList(10, 14) shouldBe listOf("동행", "1", null, "010-0000-0001")
            ledger[3].subList(20, 26) shouldBe listOf("취소", "2026-10-01 09:00:00", null, null, "상태 이력 미기록", null)
            ledger[3][27] shouldBe "줄\n바꿈"
            ledger[4].subList(0, 7) shouldBe listOf("연수 신청", "$TRAINING_A", "7", "1", "10", "참가자1", "연수자프로필")
            ledger[5].subList(14, 15) shouldBe listOf("82")

            val history = book.getSheet("상태 이력").values()
            history shouldBe
                listOf(
                    listOf("신청 ID", "접수번호", "이력 ID", "상태", "기록 시각"),
                    listOf("$CURRENT", "9", "3", "대기", "2026-10-03 09:00:00"),
                    listOf("$CURRENT", "9", "4", "확정", "2026-10-04 09:00:00"),
                    listOf("$CANCELLED", "5", "1", "확정", "2026-10-01 09:00:00"),
                    listOf("$CANCELLED", "5", "2", "취소", "2026-10-02 09:00:00"),
                    listOf("$TRAINING_A", "7", "1", "확정", "2026-10-01 09:00:00"),
                    listOf("$TRAINING_B", "8", "1", "확정", "2026-10-01 09:00:00"),
                )

            val meta = book.getSheet("메타").values().associate { it[0] to it.getOrNull(1) }
            meta["범위"] shouldBe "SELECTED"
            meta["선택 신청 수"] shouldBe "5"
            meta["신청 행 수"] shouldBe "5"
            meta["상태 이력 행 수"] shouldBe "6"
            meta["상태 이력 미기록 신청 수"] shouldBe "1"
            meta["미복원 답변 수"] shouldBe "1"
            meta["대기 순번"] shouldBe "원천 미제공(열 없음)"
        }
    }

    @Test
    fun `회차 정의 일시가 UTC Instant면 운영일 필터와 운영 일시 열을 KST로 본다`() {
        // KST 2026-10-31 08:00~09:00. UTC 날짜(10/30)로 비교하면 공급자 필터 결과를 거절했다(#107)
        application.on(
            "POST $TARGET",
            body = snapshot(listOf(target(CURRENT, startedAt = "2026-10-30T23:00:00Z", endedAt = "2026-10-31T00:00:00Z")), null),
        )
        val response = send(mapOf("template" to "APPLICATION_LEDGER", "scope" to "FILTER", "filter" to mapOf("eventDate" to "2026-10-31")))

        response.status shouldBe 200
        XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { book ->
            val ledger = book.getSheetAt(0).values()
            val start = ledger[0].indexOf("운영 시작")
            ledger[1].subList(start, start + 2) shouldBe listOf("2026-10-31 08:00", "2026-10-31 09:00")
        }
    }

    @Test
    fun `필터 전체는 상태 배열·참가자 검색·직업·운영일·정렬을 Application에 그대로 전달한다`() {
        application.on("POST $TARGET", body = snapshot(listOf(target(CANCELLED, status = "CANCELLED"), target(CURRENT)), null))
        val filter =
            mapOf(
                "status" to listOf("CONFIRMED", "CANCELLED"),
                "q" to "참가자",
                "occupation" to "TEACHER",
                "eventDate" to "2026-10-31",
                "sort" to "RECEIPT_ASC",
                "programType" to "STANDARD",
                "programId" to 73,
            )
        val response = send(mapOf("template" to "APPLICATION_LEDGER", "scope" to "FILTER", "filter" to filter))
        response.status shouldBe 200
        val sent = StubProvider.JSON.readTree(application.requests.single().body).path("filter")
        sent.path("status").values().map { it.asString() } shouldBe listOf("CONFIRMED", "CANCELLED")
        sent.path("q").asString() shouldBe "참가자"
        sent.path("occupation").asString() shouldBe "TEACHER"
        sent.path("eventDate").asString() shouldBe "2026-10-31"
        sent.path("sort").asString() shouldBe "RECEIPT_ASC"
        XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { book ->
            // 공급자 정렬 순서 그대로
            book
                .getSheetAt(0)
                .values()
                .drop(1)
                .map { it[1] } shouldBe listOf("$CANCELLED", "$CURRENT")
            book.getSheet("메타").values().associate { it[0] to it.getOrNull(1) }["상태 조건"] shouldBe "CONFIRMED,CANCELLED"
        }
    }

    @Test
    fun `기존 단일 상태 필터는 배열로 호환되고 상태 null은 모든 상태다`() {
        application.on("POST $TARGET", body = snapshot(listOf(target(CANCELLED, status = "CANCELLED")), null, total = 1))
        send(mapOf("template" to "STANDARD", "scope" to "FILTER", "filter" to mapOf("status" to "CANCELLED"))).status shouldBe 200
        StubProvider.JSON
            .readTree(application.requests.single().body)
            .path("filter")
            .path("status")
            .values()
            .map { it.asString() } shouldBe listOf("CANCELLED")

        application.requests.clear()
        send(mapOf("template" to "APPLICATION_LEDGER", "scope" to "FILTER", "filter" to mapOf("status" to null))).status shouldBe 200
        val sent = StubProvider.JSON.readTree(application.requests.single().body).path("filter")
        sent.has("status") shouldBe true
        sent.path("status").isNull shouldBe true
        sent.has("q") shouldBe false
    }

    @Test
    fun `잘못된 필터는 공급자 호출 전에 400이다`() {
        listOf(
            mapOf("status" to emptyList<String>()),
            mapOf("status" to listOf("CONFIRMED", "CONFIRMED")),
            mapOf("q" to " "),
            mapOf("q" to "가".repeat(51)),
            mapOf("occupation" to "ASTRONAUT"),
            mapOf("eventDate" to "2026-13-01"),
            mapOf("sort" to "NAME_ASC"),
        ).forEach { filter ->
            send(mapOf("template" to "APPLICATION_LEDGER", "scope" to "FILTER", "filter" to filter)).status shouldBe 400
        }
        send(mapOf("template" to "ATTENDANCE", "scope" to "FILTER", "traineeId" to 10, "filter" to mapOf("q" to "남"))).status shouldBe 400
        send(mapOf("template" to "APPLICATION_LEDGER", "scope" to "SELECTED", "applicationIds" to listOf(CURRENT), "programId" to 73))
            .status shouldBe 400
        application.requests.size shouldBe 0
    }

    @Test
    fun `접수번호·이력이 없는 구버전 원천, 사람·연수자 누락, 운영일 불일치는 파일을 만들지 않는다`() {
        select(listOf(target(CURRENT) - "receiptNumber"))
        send(selected(listOf(CURRENT))).status shouldBe 502

        select(listOf(target(CURRENT) - "history"))
        send(selected(listOf(CURRENT))).status shouldBe 502

        select(listOf(target(CURRENT, participant = 4)))
        send(selected(listOf(CURRENT))).status shouldBe 502

        select(listOf(target(TRAINING_A, type = "TRAINING", program = 81, trainee = 11)))
        send(selected(listOf(TRAINING_A))).status shouldBe 502

        // 연수자는 있으나 다른 참가자에 이어져 있다
        select(listOf(target(TRAINING_A, participant = 3, type = "TRAINING", program = 81, trainee = 10)))
        send(selected(listOf(TRAINING_A))).status shouldBe 502

        application.on("POST $TARGET", body = snapshot(listOf(target(CURRENT)), null, total = 1))
        send(mapOf("template" to "APPLICATION_LEDGER", "scope" to "FILTER", "filter" to mapOf("eventDate" to "2026-11-01"))).status shouldBe
            502

        // 다른 행사 신청이 섞이면 거절
        select(listOf(target(CURRENT) + ("expoId" to UUID.randomUUID())))
        send(selected(listOf(CURRENT))).status shouldBe 502

        // 응답 누락
        application.on("POST $TARGET/selection", body = mapOf("items" to listOf(target(CURRENT)), "totalElements" to 1))
        send(selected(listOf(CURRENT, CANCELLED))).status shouldBe 502
    }

    @Test
    fun `동명 문항은 문항 ID로 구분한 열로 보존하고 null 답변은 빈칸, false는 그대로 둔다`() {
        val questions =
            QUESTIONS +
                listOf(
                    // 앞 문항과 제목이 같은 뒤 문항
                    mapOf("id" to 322, "title" to "참가 학교", "formType" to "SENTENCE"),
                )
        select(
            listOf(target(CURRENT, answers = mapOf("320" to "광주초", "322" to "보조학교", "321" to false, "319" to null), questions = questions)),
        )
        val response = send(selected(listOf(CURRENT)))
        response.status shouldBe 200
        XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { book ->
            val ledger = book.getSheetAt(0).values()
            ledger[0].drop(26) shouldBe listOf("초등학교 학년", "참가 학교", "확인", "참가 학교 (문항 322)")
            ledger[1].drop(26) shouldBe listOf(null, "광주초", "false", "보조학교")
            book.getSheet("메타").values().associate { it[0] to it.getOrNull(1) }["미복원 답변 수"] shouldBe "0"
        }
    }

    @Test
    fun `본인 지역과 과거 신청 지역은 선택 필터 모두 별도 열로 보존한다`() {
        val questions = listOf(mapOf("id" to 1, "title" to "지역", "formType" to "REGION"))
        val rows =
            listOf(
                target(CURRENT, participant = 1, answers = mapOf("1" to "GWANGJU"), questions = questions),
                target(COMPANION, participant = 2, answers = mapOf("1" to "GWANGJU"), questions = questions),
            )
        select(rows)
        application.on("POST $TARGET", body = snapshot(rows, null))
        val outputs =
            listOf(selected(listOf(CURRENT, COMPANION)), mapOf("template" to "APPLICATION_LEDGER", "scope" to "FILTER")).map { request ->
                val response = send(request)
                response.status shouldBe 200
                Files.createDirectories(Path.of("build/issue88"))
                Files.write(Path.of("build/issue88/application-ledger.xlsx"), response.contentAsByteArray)
                XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { it.getSheetAt(0).values() }
            }
        outputs[0] shouldBe outputs[1]
        val ledger = outputs[0]
        val headers = ledger[0]
        headers[headers.indexOf("소속") + 1] shouldBe "본인 지역"
        ledger.drop(1).map { it[headers.indexOf("본인 지역")] } shouldBe listOf("광주", "전남")
        ledger.drop(1).map { it[headers.indexOf("지역")] } shouldBe listOf("GWANGJU", "GWANGJU")
        ledger.forEach { it.size shouldBe headers.size }
    }

    private fun send(body: Map<String, Any?>): MockHttpServletResponse =
        mvc
            .perform(
                post("/excel/expos/$EVENT/downloads")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${TestJwt.token("ROLE_ADMIN")}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(StubProvider.JSON.writeValueAsString(body)),
            ).andReturn()
            .response

    private fun selected(ids: List<UUID>) = mapOf("template" to "APPLICATION_LEDGER", "scope" to "SELECTED", "applicationIds" to ids)

    private fun select(items: List<Map<String, Any?>>) =
        application.on("POST $TARGET/selection", body = mapOf("items" to items, "totalElements" to items.size))

    // 검증용 실파일. 합성 값만 들어 있고 build 디렉터리에만 남는다
    private fun save(response: MockHttpServletResponse) {
        Files.createDirectories(Path.of("build/ledger"))
        Files.write(Path.of("build/ledger/application-ledger.xlsx"), response.contentAsByteArray)
    }

    private fun history(
        id: Long,
        status: String,
        at: String,
    ) = mapOf("id" to id, "status" to status, "createdAt" to Instant.parse(at))

    private fun target(
        id: UUID,
        participant: Long = 1,
        type: String = "STANDARD",
        program: Long = 73,
        trainee: Long? = null,
        receipt: Long = 1,
        status: String = "CONFIRMED",
        route: String? = null,
        history: List<Any> = listOf(history(1, "CONFIRMED", "2026-10-01T00:00:00Z")),
        answers: Any = emptyMap<String, Any>(),
        questions: Any = QUESTIONS,
        startedAt: String = "2026-10-31T09:00",
        endedAt: String = "2026-10-31T10:00",
    ): Map<String, Any?> =
        mapOf(
            "expoId" to EVENT,
            "registrationSource" to mapOf("expoId" to EVENT, "participantId" to participant),
            "application" to
                mapOf(
                    "applicationId" to id,
                    "receiptNumber" to receipt,
                    "participantId" to participant,
                    "traineeId" to trainee,
                    "programType" to type,
                    "programId" to program,
                    "groupId" to EVENT,
                    "status" to status,
                    "createdAt" to Instant.parse("2026-10-01T00:00:00Z"),
                    "formId" to EVENT,
                    "formVersion" to 1,
                    "definition" to
                        mapOf(
                            "expoId" to EVENT,
                            "id" to program,
                            "programType" to type,
                            "title" to if (type == "TRAINING") "연수$program" else "골든벨",
                            "startedAt" to startedAt,
                            "endedAt" to endedAt,
                            "place" to "강당",
                        ),
                    "questions" to questions,
                    "answers" to answers,
                    "admissionRoute" to route,
                    "history" to history,
                ),
        )

    // 테스트 편의: application 안의 키를 지운다
    private operator fun Map<String, Any?>.minus(key: String): Map<String, Any?> =
        this + ("application" to (this["application"] as Map<*, *>).filterKeys { it != key })

    private fun snapshot(
        items: List<Any>,
        next: String?,
        total: Int = items.size,
    ) = mapOf(
        "snapshotId" to EVENT,
        "snapshotAt" to Instant.parse("2026-01-01T00:00:00Z"),
        "expiresAt" to Instant.parse("2099-01-01T00:00:00Z"),
        "totalElements" to total,
        "items" to items,
        "nextCursor" to next,
    )

    private fun person(
        id: Long,
        phone: String?,
        notification: String?,
        representative: Long?,
    ) = mapOf(
        "participantId" to id,
        "name" to "참가자$id",
        "phoneNumber" to phone,
        "notificationPhoneNumber" to notification,
        "personalInformationStatus" to true,
        "applicationType" to "PRE",
        "information" to mapOf("answers" to emptyMap<String, Any>()),
        "surveyAnswer" to null,
        "occupation" to "TEACHER",
        "representativeId" to representative,
        "region" to
            when (id) {
                1L -> "GWANGJU"
                2L -> "JEONNAM"
                else -> null
            },
        "school" to "소마초",
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
