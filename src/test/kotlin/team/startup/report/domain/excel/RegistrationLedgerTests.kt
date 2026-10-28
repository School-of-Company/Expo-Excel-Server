package team.startup.report.domain.excel

import io.kotest.matchers.collections.shouldNotContain
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
import team.startup.report.domain.excel.presentation.controller.ExcelDownloadController
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
private val OTHER_EVENT = UUID.fromString("99999999-9999-9999-9999-999999999999")
private val SOURCE = "/internal/expos/$EVENT/preregistrations/report-targets"
private val MORNING = "APPLICATION:$EVENT:11"
private val AFTERNOON = "APPLICATION:$EVENT:12"
private val COMPANION = "APPLICATION:$EVENT:13"
private val WAITING = "RESERVATION:$EVENT:req-abc"
private val ADDITION = "RESERVATION:$EVENT:req-def"
private val FIELD_CANCELLED = "APPLICATION:$EVENT:14"

// 합성 fixture. 모양은 Application #284 ReportPreregistrationTarget(producer DTO)과 User 참가자 상세 그대로다. 실제 번호·토큰 없음.
private val QUESTIONS =
    listOf(
        mapOf("id" to "q1", "title" to "학교", "formType" to "SENTENCE", "order" to 1),
        mapOf("id" to "q2", "title" to "학년", "formType" to "DROPDOWN", "order" to 2, "jsonData" to mapOf("4" to "4학년")),
        mapOf("id" to "q3", "title" to "동의", "formType" to "CHECKBOX", "order" to 3),
    )

@WebMvcTest(ExcelDownloadController::class)
@Import(ExcelSecurityConfig::class, ExcelDownloadService::class, RegistrationLedgerTests.ClientConfig::class)
class RegistrationLedgerTests {
    @Autowired
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        listOf(application, user, expo).forEach {
            it.routes.clear()
            it.requests.clear()
        }
        application.on(
            "GET /internal/expos/$EVENT/field-visits?size=100",
            body =
                mapOf(
                    "items" to emptyList<Any>(),
                    "events" to emptyList<Any>(),
                    "nextCursor" to null,
                ),
        )
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
                            person(1, "PRE", phone = "010-0000-0001", notification = "010-0000-0001", representative = null),
                            // 현장 등록 동행자지만 사전등록 회차 행이 있으므로 회차 미지정 행이 아니다
                            person(2, "FIELD", phone = null, notification = "010-0000-0001", representative = 1, occupation = null),
                            // 회차가 하나도 없는 현장 등록 참가자 → 회차 미지정 행
                            person(3, "FIELD", phone = "010-0000-0003", notification = "010-0000-0003", representative = null),
                            // 사전등록을 모두 취소하고 현장 등록 → 취소 행과 회차 미지정 행 둘 다
                            person(5, "FIELD", phone = "010-0000-0005", notification = "010-0000-0005", representative = null),
                            // 회차가 없는 사전 등록 참가자는 원장 행이 아니다
                            person(4, "PRE", phone = "010-0000-0004", notification = "010-0000-0004", representative = null),
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
    fun `조건 없는 필터 전체는 참가자 회차 행·예약 행·회차 미지정 현장 등록 행을 각각 출력하고 이력을 보존한다`() {
        application.on("POST $SOURCE", body = snapshot(all().take(3), "c1", total = 6))
        application.on("GET $SOURCE?cursor=c1", body = snapshot(all().drop(3), null, total = 6))
        val response = send(mapOf("template" to "REGISTRATION_LEDGER", "scope" to "FILTER"))
        response.status shouldBe 200
        response.getHeader("Cache-Control") shouldBe "no-store"
        response.getHeader(HttpHeaders.CONTENT_DISPOSITION)!! shouldStartWith
            "attachment; filename*=UTF-8''%EB%93%B1%EB%A1%9D%EC%9B%90%EC%9E%A5_"
        StubProvider.JSON.readTree(application.requests.first().body).toString() shouldBe """{"filter":{},"limit":500}"""
        Files.createDirectories(Path.of("build/ledger"))
        Files.write(Path.of("build/ledger/registration-ledger.xlsx"), response.contentAsByteArray)

        XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { book ->
            val fullLedger = book.getSheet("등록 원장").values()
            fullLedger[0].takeLast(7) shouldBe listOf("방문일", "FIELD 상태", "세대", "현장 등록 ID", "취소 참가자 ID", "취소 전달 대기", "등록 전달 대기")
            fullLedger.drop(1).forEach { row ->
                row.takeLast(7) shouldBe
                    if (row[0] == "현장 등록(회차 미지정)") listOf("UNKNOWN", null, null, null, null, null, null) else List(7) { null }
            }
            val ledger = fullLedger.map { it.dropLast(7) }
            ledger.size shouldBe 9
            ledger[0] shouldBe
                listOf(
                    "행 유형",
                    "접수 키",
                    "신청 ID",
                    "예약 ID",
                    "참가자 ID",
                    "대표/동행",
                    "대표자 ID",
                    "예약 유형",
                    "인원",
                    "좌석 구분",
                    "이름",
                    "직업",
                    "소속",
                    "본인 지역",
                    "본인 연락처",
                    "알림 연락처",
                    "등록 방식",
                    "회차 ID",
                    "회차 제목",
                    "운영 시작",
                    "운영 종료",
                    "장소",
                    "회차 삭제",
                    "상태",
                    "확정 시각",
                    "취소 시각",
                    "취소 주체",
                    "대기 경유",
                    "답변 출처",
                    "미복원 답변 수",
                    "학교",
                    "학년",
                    "동의",
                )
            ledger[1] shouldBe
                listOf(
                    "참가자 회차 등록",
                    MORNING,
                    "11",
                    null,
                    "1",
                    "대표",
                    "1",
                    null,
                    "1",
                    "교원",
                    "참가자1",
                    "교원",
                    "소마초",
                    "광주",
                    "010-0000-0001",
                    "010-0000-0001",
                    "사전 등록",
                    "3",
                    "오전 강연",
                    "2026-10-31 09:00:00",
                    "2026-10-31 10:00:00",
                    "강당",
                    null,
                    "확정",
                    "2026-10-01 09:00:00",
                    null,
                    null,
                    "아니오",
                    "본인 제출(등록 당시)",
                    null,
                    "광주초",
                    "4학년",
                    "false",
                )
            // 같은 사람의 다른 회차 취소 행: 연결 예약 없는 과거 행은 제출본·대기 경유가 미기록
            ledger[2] shouldBe
                listOf(
                    "참가자 회차 등록",
                    AFTERNOON,
                    "12",
                    null,
                    "1",
                    "대표",
                    "1",
                    null,
                    "1",
                    "그 외",
                    "참가자1",
                    "교원",
                    "소마초",
                    "광주",
                    "010-0000-0001",
                    "010-0000-0001",
                    "사전 등록",
                    "4",
                    "오후 강연",
                    "2026-10-31 13:00:00",
                    null,
                    null,
                    "삭제됨",
                    "취소",
                    "2026-10-01 09:00:00",
                    "2026-10-02 09:00:00",
                    "관리자",
                    "미기록",
                    "미기록(연결 예약 없음)",
                    null,
                    null,
                    null,
                    null,
                )
            // 동행자: 본인 번호 없음, 알림은 대표자 번호, 답변은 대표 제출본으로 표시
            ledger[3].subList(0, 17) shouldBe
                listOf(
                    "참가자 회차 등록",
                    COMPANION,
                    "13",
                    null,
                    "2",
                    "동행",
                    "1",
                    null,
                    "1",
                    "미기록",
                    "참가자2",
                    // User 현재 직업·본인 번호 null은 대표 제출본(TEACHER, 010-0000-0001)으로 채우지 않는다
                    null,
                    "소마초",
                    "전남",
                    null,
                    "010-0000-0001",
                    // User 현재 등록 방식이 FIELD여도 사전등록 원천 행은 사전이다
                    "사전 등록",
                )
            ledger[3].subList(27, 30) shouldBe listOf("예", "대표자 제출(등록 당시)", null)
            // 예약 행: 예약 ID는 참가자 ID 열에 들어가지 않는다
            ledger[4].subList(0, 17) shouldBe
                listOf(
                    "예약(User 미저장)",
                    WAITING,
                    null,
                    "req-abc",
                    null,
                    null,
                    null,
                    "일행 예약",
                    "3",
                    "교원 0 · 그 외 0 · 미기록 3",
                    "대기대표",
                    "학부모",
                    null,
                    null,
                    "010-0000-0009",
                    null,
                    "사전 등록",
                )
            ledger[4].subList(23, 25) shouldBe listOf("대기", null)
            ledger[5].subList(0, 8) shouldBe listOf("예약(User 미저장)", ADDITION, null, "req-def", null, null, "1", "동행자 추가")
            ledger[5][23] shouldBe "복구 중"
            ledger[6].subList(0, 5) shouldBe listOf("참가자 회차 등록", FIELD_CANCELLED, "14", null, "5")
            ledger[6][16] shouldBe "사전 등록"
            ledger[6][23] shouldBe "취소"
            // 사전등록을 모두 취소하고 현장 등록한 참가자: 취소 행과 현재 회차 미지정 행이 둘 다 남는다
            ledger[8].subList(0, 5) shouldBe listOf("현장 등록(회차 미지정)", null, null, null, "5")
            ledger[8].subList(16, 24) shouldBe listOf("현장 등록", null, "회차 미지정", null, null, null, null, "해당 없음(회차 미지정)")
            ledger[7].subList(0, 24) shouldBe
                listOf(
                    "현장 등록(회차 미지정)",
                    null,
                    null,
                    null,
                    "3",
                    "대표",
                    null,
                    null,
                    "1",
                    null,
                    "참가자3",
                    "교원",
                    "소마초",
                    null,
                    "010-0000-0003",
                    "010-0000-0003",
                    "현장 등록",
                    null,
                    "회차 미지정",
                    null,
                    null,
                    null,
                    null,
                    "해당 없음(회차 미지정)",
                )
            ledger[7].subList(28, 33) shouldBe listOf("User 등록 답변", null, "현장학교", null, null)
            ledger.drop(1).map { it[4] } shouldNotContain "req-abc"

            book.getSheet("등록 상태 이력").values() shouldBe
                listOf(
                    listOf("접수 키", "순번", "상태", "기록 시각", "실행 유형", "실행 참가자 ID", "실행 관리자 ID"),
                    listOf(MORNING, "1", "확정", "2026-10-01 09:00:00", "참가자", "1", null),
                    listOf(AFTERNOON, "1", "확정", "시각 미기록", "미기록", null, null),
                    listOf(AFTERNOON, "2", "취소", "2026-10-02 09:00:00", "관리자", null, "9"),
                    listOf(COMPANION, "1", "확정", "2026-10-01 09:00:00", "시스템", null, null),
                    listOf(FIELD_CANCELLED, "1", "확정", "2026-10-01 09:00:00", "참가자", "5", null),
                    listOf(FIELD_CANCELLED, "2", "취소", "2026-10-02 09:00:00", "참가자", "5", null),
                )
            val meta = book.getSheet("메타").values().associate { it[0] to it.getOrNull(1) }
            meta["참가자 회차 등록 행 수"] shouldBe "4"
            meta["예약 행 수"] shouldBe "2"
            meta["회차 미지정 현장 등록 행 수"] shouldBe "2"
            meta["회차 미지정 행 포함"] shouldBe "포함(조건 없는 필터 전체)"
            meta["상태 이력 행 수"] shouldBe "6"
            meta["시각 미기록 이력 수"] shouldBe "1"
            meta["제출본 미기록 행 수"] shouldBe "2"
            meta["상태 조건"] shouldBe "전체"
        }
    }

    @Test
    fun `선택 key는 고른 순서로 출력하고 회차 미지정 행을 섞지 않는다`() {
        application.on("POST $SOURCE/selection", body = mapOf("items" to listOf(all()[0], all()[3]), "totalElements" to 2))
        val response = send(selected(listOf(WAITING, MORNING)))
        response.status shouldBe 200
        StubProvider.JSON
            .readTree(application.requests.single().body)
            .path("keys")
            .values()
            .map { it.asString() } shouldBe listOf(WAITING, MORNING)
        XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { book ->
            book
                .getSheet("등록 원장")
                .values()
                .drop(1)
                .map { it[1] } shouldBe listOf(WAITING, MORNING)
            book.getSheet("메타").values().associate { it[0] to it.getOrNull(1) }["회차 미지정 행 포함"]!! shouldStartWith "제외"
        }
    }

    @Test
    fun `회차·상태·검색 조건은 Application에 그대로 전달하고 회차 미지정 행은 제외한다`() {
        application.on("POST $SOURCE", body = snapshot(listOf(all()[1]), null, total = 1))
        val response =
            send(
                mapOf(
                    "template" to "REGISTRATION_LEDGER",
                    "scope" to "FILTER",
                    "registrationFilter" to mapOf("sessionId" to 4, "status" to "CANCELLED", "q" to "참가자"),
                ),
            )
        response.status shouldBe 200
        StubProvider.JSON.readTree(application.requests.single().body).toString() shouldBe
            """{"filter":{"sessionId":4,"status":["CANCELLED"],"q":"참가자"},"limit":500}"""
        XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { book ->
            book.getSheet("등록 원장").values().size shouldBe 2
        }
    }

    @Test
    fun `잘못된 요청은 공급자 호출 전에 400이다`() {
        listOf(
            selected(emptyList()),
            selected(listOf(MORNING, MORNING)),
            selected((1..10_001).map { "APPLICATION:$EVENT:$it" }),
            selected(listOf(MORNING)) + ("registrationFilter" to emptyMap<String, Any>()),
            selected(listOf(MORNING)) + ("applicationIds" to listOf(EVENT)),
            mapOf("template" to "REGISTRATION_LEDGER", "scope" to "FILTER", "keys" to listOf(MORNING)),
            mapOf("template" to "REGISTRATION_LEDGER", "scope" to "FILTER", "filter" to emptyMap<String, Any>()),
            registration(mapOf("status" to emptyList<String>())),
            registration(mapOf("status" to listOf("WAITING", "WAITING"))),
            registration(mapOf("q" to " ")),
            registration(mapOf("q" to "가".repeat(51))),
            registration(mapOf("sessionId" to 0)),
            registration(mapOf("status" to "DONE")),
            registration(mapOf("visitDate" to "2026-10-31", "sessionId" to 3)),
            registration(mapOf("fieldStatus" to "ACTIVE", "status" to "CONFIRMED")),
            registration(mapOf("visitDate" to "unknown")),
            registration(mapOf("page" to 0)),
            mapOf("template" to "STANDARD", "scope" to "SELECTED", "applicationIds" to listOf(EVENT), "keys" to listOf(MORNING)),
        ).forEach { send(it).status shouldBe 400 }
        application.requests.size shouldBe 0
    }

    @Test
    fun `예약·참가자 ID 혼동, 행사·key 불일치, 응답 누락, 원천 형식 오류는 파일을 만들지 않는다`() {
        fun selectedFails(item: Map<String, Any?>) {
            application.on("POST $SOURCE/selection", body = mapOf("items" to listOf(item), "totalElements" to 1))
            send(selected(listOf(item["key"] as String))).status shouldBe 502
        }
        selectedFails(all()[3] + ("participantId" to 3))
        selectedFails(all()[0] + ("participantId" to null))
        selectedFails(all()[0] + ("applicationId" to 99))
        selectedFails(all()[0] + ("expoId" to OTHER_EVENT))
        selectedFails(all()[0] + ("participantId" to 7))
        selectedFails(all()[0] + ("participant" to mapOf("participantId" to 2)))
        selectedFails(all()[3] + ("status" to "CONFIRMED"))
        selectedFails(all()[0] + ("registrationSnapshot" to submission("합성", "010", "TEACHER", "{broken")))

        application.on("POST $SOURCE/selection", body = mapOf("items" to listOf(all()[0]), "totalElements" to 1))
        send(selected(listOf(MORNING, AFTERNOON))).status shouldBe 502

        // 필터와 다른 행, 페이지 합계 불일치
        application.on("POST $SOURCE", body = snapshot(listOf(all()[0]), null, total = 1))
        send(registration(mapOf("status" to "CANCELLED"))).status shouldBe 502
        application.on("POST $SOURCE", body = snapshot(listOf(all()[0]), null, total = 2))
        send(registration(emptyMap())).status shouldBe 502
    }

    @Test
    fun `공급자 상한·만료·없음 상태를 보존한다`() {
        application.on("POST $SOURCE", status = 422, body = mapOf("status" to 422))
        send(registration(emptyMap())).status shouldBe 413
        application.on("POST $SOURCE", body = snapshot(emptyList(), null, total = 10_001))
        send(registration(emptyMap())).status shouldBe 413
        application.on("POST $SOURCE", body = snapshot(listOf(all()[0]), "c1", total = 2))
        application.on("GET $SOURCE?cursor=c1", status = 410, body = mapOf("status" to 410))
        send(registration(emptyMap())).status shouldBe 410
        application.on("POST $SOURCE/selection", status = 404, body = mapOf("status" to 404))
        send(selected(listOf(MORNING))).status shouldBe 404
        application.on(
            "POST $SOURCE",
            body =
                snapshot(listOf(all()[0]), null, total = 1) + ("expiresAt" to Instant.parse("2020-01-01T00:00:00Z")),
        )
        send(registration(emptyMap())).status shouldBe 410
    }

    @Test
    fun `본인 지역은 현재 프로필이고 대표 제출 지역과 출처는 선택 필터 모두 보존한다`() {
        val submitted =
            submission("대표", "synthetic-contact", "TEACHER", """{"지역":"GWANGJU"}""") +
                ("questions" to listOf(mapOf("id" to "region", "title" to "지역", "formType" to "REGION")))
        val rows = listOf(all()[0], all()[2]).map { it + ("registrationSnapshot" to submitted) }
        application.on("POST $SOURCE/selection", body = mapOf("items" to rows, "totalElements" to 2))
        application.on("POST $SOURCE", body = snapshot(rows, null, total = 2))
        val outputs =
            listOf(selected(listOf(MORNING, COMPANION)), registration(mapOf("sessionId" to 3))).map { request ->
                val response = send(request)
                response.status shouldBe 200
                Files.createDirectories(Path.of("build/issue88"))
                Files.write(Path.of("build/issue88/registration-ledger.xlsx"), response.contentAsByteArray)
                XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { it.getSheetAt(0).values() }
            }
        outputs[0] shouldBe outputs[1]
        val ledger = outputs[0]
        val headers = ledger[0]
        headers[headers.indexOf("소속") + 1] shouldBe "본인 지역"
        ledger.drop(1).map { it[headers.indexOf("본인 지역")] } shouldBe listOf("광주", "전남")
        ledger.drop(1).map { it[headers.indexOf("지역")] } shouldBe listOf("GWANGJU", "GWANGJU")
        ledger.drop(1).map { it[headers.indexOf("답변 출처")] } shouldBe listOf("본인 제출(등록 당시)", "대표자 제출(등록 당시)")
        ledger.forEach { it.size shouldBe headers.size }
    }

    @Test
    fun `방문일 페이지를 끝까지 읽고 PRE와 본인 프로필을 보존하며 pending과 세대 취소 이력을 셀에 출력한다`() {
        val first = visit("00000000-0000-0000-0000-000000000001", 1, "2026-10-31", generation = 2)
        val second = visit("00000000-0000-0000-0000-000000000002", 1, "2026-11-01", pending = true)
        val third = visit("00000000-0000-0000-0000-000000000003", 2, "2026-10-31", status = "CANCELLED")
        val fourth = visit("00000000-0000-0000-0000-000000000004", 5, "2026-10-31", cancellationPending = true)
        val events = listOf(event(first, 1, "REGISTERED"), event(first, 1, "CANCELLED"), event(first, 2, "REGISTERED"))
        application.on(
            "GET /internal/expos/$EVENT/field-visits?size=100",
            body =
                mapOf(
                    "items" to listOf(first),
                    "events" to events,
                    "nextCursor" to first["id"],
                ),
        )
        application.on(
            "GET /internal/expos/$EVENT/field-visits?cursor=${first["id"]}&size=100",
            body =
                mapOf("items" to listOf(second, third, fourth), "events" to emptyList<Any>(), "nextCursor" to null),
        )
        application.on("POST $SOURCE", body = snapshot(listOf(all()[0]), null, total = 1))
        val response = send(registration(emptyMap()))
        response.status shouldBe 200
        Files.createDirectories(Path.of("build/issue92"))
        Files.write(Path.of("build/issue92/field-visit-ledger.xlsx"), response.contentAsByteArray)
        XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { book ->
            val data = book.getSheet("등록 원장").values()
            val headers = data.first()
            val rows = data.drop(1).map { headers.zip(it).toMap() }
            rows.size shouldBe 6
            rows.first()["접수 키"] shouldBe MORNING
            rows.first()["등록 방식"] shouldBe "사전 등록"
            val field = rows.filter { it["행 유형"] == "현장 방문일 등록" }
            field.map { it["상태"] } shouldBe listOf("등록 완료", "등록 전달 대기", "취소", "취소 전달 대기")
            field[1]["확정 시각"] shouldBe null
            field[3]["확정 시각"] shouldBe null
            field.map { it["방문일"] } shouldBe listOf("2026-10-31", "2026-11-01", "2026-10-31", "2026-10-31")
            field[2]["본인 연락처"] shouldBe null
            field[2]["알림 연락처"] shouldBe "010-0000-0001"
            field[2]["본인 지역"] shouldBe "전남"
            field[2]["취소 참가자 ID"] shouldBe "1"
            field[2]["취소 시각"] shouldBe "2026-10-22 10:00:00"
            rows.single { it["참가자 ID"] == "3" }["방문일"] shouldBe "UNKNOWN"
            book
                .getSheet("현장 등록 세대 이력")
                .values()
                .drop(1)
                .map { it[1] to it[2] } shouldBe
                listOf("1" to "REGISTERED", "1" to "CANCELLED", "2" to "REGISTERED")
            book.getSheet("등록 상태 이력").values().drop(2).forEach {
                it[5] shouldBe "1"
                it[6] shouldBe null
            }
            book.getSheet("방문일별 현장 인원").values().drop(1) shouldBe
                listOf(listOf("2026-10-31", "3", "1", "1", "0", "1"), listOf("2026-11-01", "1", "0", "0", "1", "0"))
            data.forEach { it.size shouldBe headers.size }
        }
    }

    @Test
    fun `날짜 현장상태 검색 필터와 선택 키는 FIELD 원천을 쓰며 UNKNOWN과 PRE를 날짜 인원에 섞지 않는다`() {
        val row = visit("00000000-0000-0000-0000-000000000001", 2, "2026-10-31", status = "CANCELLED")
        val page = mapOf("items" to listOf(row), "events" to listOf(event(row, 1, "CANCELLED")), "nextCursor" to null)
        application.on("GET /internal/expos/$EVENT/field-visits?visitDate=2026-10-31&size=100", body = page)
        application.on("GET /internal/expos/$EVENT/field-visits?size=100", body = page)
        listOf(
            registration(mapOf("visitDate" to "2026-10-31", "fieldStatus" to "CANCELLED", "q" to "참가자2")),
            selected(listOf("FIELD:$EVENT:${row["id"]}")),
        ).forEach { request ->
            val response = send(request)
            response.status shouldBe 200
            XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { book ->
                val data = book.getSheet("등록 원장").values()
                data.size shouldBe 2
                data[1][4] shouldBe "2"
                data[1].takeLast(7).take(2) shouldBe listOf("2026-10-31", "CANCELLED")
            }
        }
        application.requests.all { it.method == "GET" } shouldBe true
        send(registration(mapOf("visitDate" to "2026-10-31", "fieldStatus" to "ACTIVE"))).status shouldBe 400
        send(selected(listOf("FIELD:$OTHER_EVENT:${row["id"]}"))).status shouldBe 400
    }

    @Test
    fun `FIELD 공급자 장애 반복 커서 잘못된 날짜 행사 참가자와 이벤트는 부분 파일 없이 실패한다`() {
        val row = visit("00000000-0000-0000-0000-000000000001", 1, "2026-10-31")
        val request = registration(mapOf("visitDate" to "2026-10-31"))
        val path = "GET /internal/expos/$EVENT/field-visits?visitDate=2026-10-31&size=100"
        listOf(
            row + ("expoId" to OTHER_EVENT),
            row + ("visitDate" to "2026-11-01"),
            row + ("participantId" to 999),
            row + ("generation" to 0),
            row - "deliveryPending",
            row - "cancellationPending",
            row + ("deliveryPending" to null),
        ).forEach {
            application.on(path, body = mapOf("items" to listOf(it), "events" to emptyList<Any>(), "nextCursor" to null))
            send(request).status shouldBe 502
        }
        application.on(path, body = mapOf("items" to listOf(row), "events" to listOf(event(row, 2, "REGISTERED")), "nextCursor" to null))
        send(request).status shouldBe 502
        application.on(path, status = 503, body = emptyMap<String, Any>())
        send(request).status shouldBe 502
        val repeat = mapOf("items" to listOf(row), "events" to emptyList<Any>(), "nextCursor" to row["id"])
        application.on(path, body = repeat)
        application.on("GET /internal/expos/$EVENT/field-visits?visitDate=2026-10-31&cursor=${row["id"]}&size=100", body = repeat)
        send(request).status shouldBe 502
    }

    @Test
    fun `PRE와 FIELD 혼합 선택은 요청 순서와 참가자 식별자를 보존한다`() {
        val row = visit("00000000-0000-0000-0000-000000000001", 2, "2026-10-31")
        val key = "FIELD:$EVENT:${row["id"]}"
        application.on(
            "GET /internal/expos/$EVENT/field-visits?size=100",
            body =
                mapOf(
                    "items" to listOf(row),
                    "events" to listOf(event(row, 1, "REGISTERED")),
                    "nextCursor" to null,
                ),
        )
        application.on("POST $SOURCE/selection", body = mapOf("items" to listOf(all()[0]), "totalElements" to 1))
        val response = send(selected(listOf(key, MORNING)))
        response.status shouldBe 200
        XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { book ->
            book
                .getSheet("등록 원장")
                .values()
                .drop(1)
                .map { it[1] to it[4] } shouldBe listOf(key to "2", MORNING to "1")
        }
        StubProvider.JSON
            .readTree(application.requests.single { it.method == "POST" }.body)
            .path("keys")
            .toString() shouldBe
            """["$MORNING"]"""
    }

    private fun visit(
        id: String,
        participant: Long,
        date: String,
        status: String = "ACTIVE",
        generation: Int = 1,
        pending: Boolean = false,
        cancellationPending: Boolean = false,
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "expoId" to EVENT,
            "participantId" to participant,
            "visitDate" to date,
            "status" to status,
            "generation" to generation,
            "registeredAt" to "2026-10-22T00:00:00Z",
            "cancelledAt" to
                if (status ==
                    "CANCELLED"
                ) {
                    "2026-10-22T01:00:00Z"
                } else {
                    null
                },
            "cancelledBy" to
                if (status ==
                    "CANCELLED"
                ) {
                    1
                } else {
                    null
                },
            "cancellationPending" to cancellationPending,
            "deliveryPending" to pending,
        )

    private fun event(
        row: Map<String, Any?>,
        generation: Int,
        type: String,
    ) = mapOf(
        "registrationId" to row["id"],
        "generation" to generation,
        "eventType" to type,
        "occurredAt" to
            if (type ==
                "CANCELLED"
            ) {
                "2026-10-22T01:00:00Z"
            } else {
                "2026-10-22T00:00:00Z"
            },
        "actorParticipantId" to 1,
        "operationId" to EVENT,
    )

    private fun all(): List<Map<String, Any?>> {
        val rep = submission("합성대표", "010-0000-0001", "TEACHER", """{"학교":"광주초","학년":"4","동의":false}""")
        return listOf(
            row(MORNING, "APPLICATION", "CONFIRMED", applicationId = 11, participantId = 1, representativeId = 1, representative = true) +
                mapOf(
                    "seatCategory" to "TEACHER",
                    "createdAt" to Instant.parse("2026-10-01T00:00:00Z"),
                    "history" to listOf(transition("CONFIRMED", "2026-10-01T00:00:00Z", "USER", participant = 1)),
                    "promotedFromWaiting" to false,
                    "registrationSnapshot" to rep,
                    "participant" to mapOf("participantId" to 1),
                ),
            row(AFTERNOON, "APPLICATION", "CANCELLED", applicationId = 12, participantId = 1, representativeId = 1, representative = true) +
                mapOf(
                    "seatCategory" to "OTHER",
                    "session" to
                        mapOf(
                            "sessionId" to 4,
                            "title" to "오후 강연",
                            "startedAt" to Instant.parse("2026-10-31T04:00:00Z"),
                            "endedAt" to null,
                            "place" to null,
                            "capacity" to null,
                            "teacherCapacity" to null,
                            "nonTeacherCapacity" to null,
                            "deleted" to true,
                        ),
                    "createdAt" to Instant.parse("2026-10-01T00:00:00Z"),
                    "cancelledAt" to Instant.parse("2026-10-02T00:00:00Z"),
                    "cancellationActorType" to "ADMIN",
                    "cancellationActorId" to 9,
                    "history" to
                        listOf(
                            transition("CONFIRMED", null, "UNKNOWN"),
                            transition("CANCELLED", "2026-10-02T00:00:00Z", "ADMIN", admin = 9),
                        ),
                ),
            row(
                COMPANION,
                "APPLICATION",
                "CONFIRMED",
                applicationId = 13,
                participantId = 2,
                representativeId = 1,
                representative = false,
            ) +
                mapOf(
                    "seatCategory" to "UNRECORDED",
                    "createdAt" to Instant.parse("2026-10-01T00:00:00Z"),
                    "history" to listOf(transition("CONFIRMED", "2026-10-01T00:00:00Z", "SYSTEM")),
                    "promotedFromWaiting" to true,
                    "registrationSnapshot" to rep,
                ),
            row(WAITING, "RESERVATION", "WAITING", reservationId = "req-abc") +
                mapOf(
                    "reservationKind" to "PARTY",
                    "applicants" to 3,
                    "seatCounts" to mapOf("teacher" to 0, "other" to 0, "unrecorded" to 3),
                    "registrationSnapshot" to submission("대기대표", "010-0000-0009", "PARENT", """{"학교":"전남중"}"""),
                ),
            row(ADDITION, "RESERVATION", "PENDING", reservationId = "req-def", representativeId = 1) +
                mapOf(
                    "reservationKind" to "COMPANION_ADDITION",
                    "seatCounts" to mapOf("teacher" to 1, "other" to 0, "unrecorded" to 0),
                    "promotedFromWaiting" to true,
                    "registrationSnapshot" to submission("합성대표", "010-0000-0001", "TEACHER", "{}"),
                ),
            row(
                FIELD_CANCELLED,
                "APPLICATION",
                "CANCELLED",
                applicationId = 14,
                participantId = 5,
                representativeId = 5,
                representative = true,
            ) +
                mapOf(
                    "createdAt" to Instant.parse("2026-10-01T00:00:00Z"),
                    "cancelledAt" to Instant.parse("2026-10-02T00:00:00Z"),
                    "cancellationActorType" to "USER",
                    "history" to
                        listOf(
                            transition("CONFIRMED", "2026-10-01T00:00:00Z", "USER", participant = 5),
                            transition("CANCELLED", "2026-10-02T00:00:00Z", "USER", participant = 5),
                        ),
                ),
        )
    }

    private fun row(
        key: String,
        type: String,
        status: String,
        applicationId: Long? = null,
        reservationId: String? = null,
        participantId: Long? = null,
        representativeId: Long? = null,
        representative: Boolean? = null,
    ): Map<String, Any?> =
        mapOf(
            "expoId" to EVENT,
            "key" to key,
            "rowType" to type,
            "status" to status,
            "applicationId" to applicationId,
            "reservationId" to reservationId,
            "participantId" to participantId,
            "representativeId" to representativeId,
            "representative" to representative,
            "reservationKind" to null,
            "applicants" to 1,
            "seatCategory" to null,
            "seatCounts" to null,
            "session" to
                mapOf(
                    "sessionId" to 3,
                    "title" to "오전 강연",
                    "startedAt" to Instant.parse("2026-10-31T00:00:00Z"),
                    "endedAt" to Instant.parse("2026-10-31T01:00:00Z"),
                    "place" to "강당",
                    "capacity" to 30,
                    "teacherCapacity" to 10,
                    "nonTeacherCapacity" to 20,
                    "deleted" to false,
                ),
            "createdAt" to null,
            "cancelledAt" to null,
            "cancellationActorType" to null,
            "cancellationActorUser" to null,
            "cancellationActorId" to null,
            "history" to emptyList<Any>(),
            "promotedFromWaiting" to null,
            "registrationSnapshot" to null,
            "participant" to null,
        )

    private fun submission(
        name: String,
        phone: String,
        occupation: String,
        information: String,
    ) = mapOf(
        "name" to name,
        "phoneNumber" to phone,
        "occupation" to occupation,
        "informationJson" to information,
        "questions" to QUESTIONS,
    )

    private fun transition(
        status: String,
        at: String?,
        actor: String,
        participant: Long? = null,
        admin: Long? = null,
    ) = mapOf(
        "status" to status,
        "createdAt" to at?.let(Instant::parse),
        "actorType" to actor,
        "actorParticipantId" to participant,
        "actorId" to admin,
    )

    private fun snapshot(
        items: List<Any>,
        next: String?,
        total: Int,
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
        type: String,
        phone: String?,
        notification: String?,
        representative: Long?,
        occupation: String? = "TEACHER",
    ) = mapOf(
        "participantId" to id,
        "name" to "참가자$id",
        "phoneNumber" to phone,
        "notificationPhoneNumber" to notification,
        "personalInformationStatus" to true,
        "applicationType" to type,
        "information" to
            mapOf<String, Any>(
                "answers" to if (id == 3L) mapOf("학교" to "현장학교") else emptyMap(),
                "questions" to QUESTIONS,
            ),
        "surveyAnswer" to null,
        "occupation" to occupation,
        "representativeId" to representative,
        "region" to
            when (id) {
                1L -> "GWANGJU"
                2L -> "JEONNAM"
                else -> null
            },
        "school" to "소마초",
    )

    private fun selected(keys: List<String>) = mapOf("template" to "REGISTRATION_LEDGER", "scope" to "SELECTED", "keys" to keys)

    private fun registration(filter: Map<String, Any?>) =
        mapOf("template" to "REGISTRATION_LEDGER", "scope" to "FILTER", "registrationFilter" to filter)

    private fun send(body: Map<String, Any?>): MockHttpServletResponse =
        mvc
            .perform(
                post("/excel/expos/$EVENT/downloads")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${TestJwt.token("ROLE_ADMIN")}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(StubProvider.JSON.writeValueAsString(body)),
            ).andReturn()
            .response

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
