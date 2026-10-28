package team.startup.report.domain.excel

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import team.startup.report.domain.excel.presentation.controller.ProgramAttendanceExcelController
import team.startup.report.domain.excel.service.impl.ExcelDownloadServiceImpl
import team.startup.report.domain.excel.service.impl.ProgramAttendanceExcelServiceImpl
import team.startup.report.global.client.ApplicationClient
import team.startup.report.global.client.AttendanceClient
import team.startup.report.global.client.ExpoClient
import team.startup.report.global.client.SessionType
import team.startup.report.global.client.StubProvider
import team.startup.report.global.client.UserClient
import team.startup.report.global.exception.ExpectedException
import team.startup.report.support.TestJwt
import team.startup.report.support.values
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.util.UUID

private val EVENT = UUID.fromString("11111111-1111-1111-1111-111111111111")
private val OTHER_EVENT = UUID.fromString("99999999-9999-9999-9999-999999999999")
private val TARGET = "/internal/expos/$EVENT/program-session-applications/report-targets"
private val HEADERS =
    listOf("회차 유형", "회차 ID", "회차 제목", "예정 시작", "예정 종료", "참가자 ID", "이름", "소속", "신청 ID", "신청 상태", "실제 출석", "출석일", "입실 시각", "비고")

/** 합성 fixture만 쓴다. 실제 전화번호·QR 토큰·원본 XLSX는 없다. */
@WebMvcTest(ProgramAttendanceExcelController::class)
@Import(
    team.startup.report.global.security.ExcelSecurityConfig::class,
    ExcelDownloadServiceImpl::class,
    ProgramAttendanceExcelServiceImpl::class,
    ProgramAttendanceExcelTests.ClientConfig::class,
)
class ProgramAttendanceExcelTests {
    @Autowired
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        listOf(application, user, expo, attendance).forEach {
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
        expo.on(
            "GET /internal/expo/$EVENT/standard-programs/7",
            body = mapOf("id" to 7, "title" to "골든벨", "startedAt" to "2026-10-31T10:00", "endedAt" to "2026-10-31T11:00"),
        )
    }

    @AfterEach
    fun checkTokens() {
        application.requests.forEach { it.token shouldBe "application-test" }
        user.requests.forEach { it.token shouldBe "user-test" }
        expo.requests.forEach { it.token shouldBe "expo-test" }
        attendance.requests.forEach { it.token shouldBe "attendance-test" }
    }

    @Test
    fun `일반 회차는 확정 신청자 출석·미출석과 확정 신청 없는 출석을 참가자 ID로 나눠 출력한다`() {
        snapshot(
            listOf(
                // 1: 취소 후 재신청 확정, 실제 출석일이 예정일(10/31)과 다르다 → 확정 한 행, 실제 날짜 유지
                app(1, participant = 1, status = "CANCELLED", at = "2026-10-01T00:00:00Z"),
                app(2, participant = 1, at = "2026-10-02T00:00:00Z"),
                app(3, participant = 1, at = "2026-10-03T00:00:00Z"),
                // 2: 확정, 출석 없음 → 미출석
                app(4, participant = 2),
                // 3: 취소 뒤 출석 기록 존재 → 불일치 시트, 상태 취소
                app(5, participant = 3, status = "CANCELLED"),
                // 4: 확정, 이름 null → 빈칸 유지
                app(6, participant = 4),
                // 6: 대기만 있고 출석 없음 → 출력 안 함
                app(7, participant = 6, status = "WAITING"),
                // 10: 연수자 ID가 다른 참가자의 참가자 ID와 겹친다. 일반 회차는 participantId로만 맞춘다
                app(8, participant = 10, trainee = 1),
            ),
        )
        people(
            listOf(
                person(1, "가참가자", "가학교"),
                person(2, "나참가자", null),
                person(3, "다참가자", "다학교"),
                person(4, null, "라학교"),
                person(6, "바참가자", null),
                person(10, "차참가자", null),
            ),
        )
        attendance.on(
            "GET /internal/program-attendances/standard/7",
            body =
                listOf(
                    record(participant = 1, date = "2026-10-21", time = "09:58"),
                    record(participant = 3, date = "2026-10-31", time = "10:01"),
                    record(participant = 4, date = "2026-10-31", time = "10:05"),
                    // 이 행사 명단에 없는 참가자(다른 행사 등) → 버리지 않고 불일치로 남긴다
                    record(participant = 77, date = "2026-10-31", time = "10:07"),
                ),
        )

        val response = send("STANDARD", 7)

        response.status shouldBe 200
        response.getHeader("Cache-Control") shouldBe "no-store"
        response.getHeader(HttpHeaders.CONTENT_DISPOSITION) shouldContain "Program_Attendance_STANDARD_7.xlsx"
        File("build/e08-synthetic-standard.xlsx").writeBytes(response.contentAsByteArray)
        XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { book ->
            book.numberOfSheets shouldBe 2
            val program = listOf("일반", "7", "골든벨", "2026-10-31 10:00", "2026-10-31 11:00")
            book.getSheet("실제 출석 결과").values() shouldBe
                listOf(
                    HEADERS,
                    program + listOf("1", "가참가자", "가학교", id(2).toString(), "확정", "출석", "2026-10-21", "09:58", null),
                    program + listOf("2", "나참가자", null, id(4).toString(), "확정", "미출석", null, null, null),
                    program + listOf("4", null, "라학교", id(6).toString(), "확정", "출석", "2026-10-31", "10:05", null),
                    program + listOf("10", "차참가자", null, id(8).toString(), "확정", "미출석", null, null, null),
                )
            book.getSheet("신청 불일치 출석").values() shouldBe
                listOf(
                    HEADERS,
                    program + listOf("3", "다참가자", "다학교", id(5).toString(), "취소", "출석", "2026-10-31", "10:01", null),
                    program + listOf("77", null, null, null, "신청 없음", "출석", "2026-10-31", "10:07", "참가자 정보 없음"),
                )
        }
        // 전 상태 신청을 단일 회차로 조회한다
        val body = application.requests.single { it.method == "POST" }.body
        body shouldContain "\"status\":null"
        body shouldContain "\"programType\":\"STANDARD\""
        body shouldContain "\"programId\":7"
    }

    @Test
    fun `연수 회차는 연수자 ID로만 연결하고 같은 숫자의 참가자 ID와 섞지 않는다`() {
        expo.on(
            "POST /internal/expo/$EVENT/training-programs/batch",
            body =
                listOf(
                    mapOf(
                        "id" to 9,
                        "title" to "연수",
                        "startedAt" to "2026-10-31 13:00",
                        "endedAt" to "2026-10-31 15:00",
                        "category" to "ESSENTIAL",
                    ),
                ),
        )
        snapshot(
            listOf(
                app(1, participant = 7, trainee = 1, type = "TRAINING", program = 9),
                // 연수자 미연결 확정 신청 → 출석 확인 불가로 표시하고 미출석으로 만들지 않는다
                app(2, participant = 8, trainee = null, type = "TRAINING", program = 9),
            ),
        )
        user.on(
            "GET /internal/expos/$EVENT/trainees/details?size=500",
            body = mapOf("items" to listOf(trainee(1, "연수자1"), trainee(7, "연수자7")), "nextCursor" to null),
        )
        attendance.on(
            "GET /internal/program-attendances/training/9",
            // traineeId 7은 참가자 7(연수자 1)이 아닌 다른 연수자다
            body = listOf(record(trainee = 7, date = "2026-10-31", time = "13:02")),
        )

        val response = send("TRAINING", 9)

        response.status shouldBe 200
        File("build/e08-synthetic-training.xlsx").writeBytes(response.contentAsByteArray)
        XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { book ->
            val program = listOf("연수", "9", "연수", "2026-10-31 13:00", "2026-10-31 15:00")
            val headers = HEADERS.map { if (it == "참가자 ID") "연수자 ID" else it }
            book.getSheet("실제 출석 결과").values() shouldBe
                listOf(
                    headers,
                    program + listOf("1", "연수자1", "연수학교", id(1).toString(), "확정", "미출석", null, null, null),
                    program + listOf(null, null, null, id(2).toString(), "확정", null, null, null, "연수자 미연결 신청: 출석 확인 불가"),
                )
            book.getSheet("신청 불일치 출석").values() shouldBe
                listOf(headers, program + listOf("7", "연수자7", "연수학교", null, "신청 없음", "출석", "2026-10-31", "13:02", null))
        }
    }

    @Test
    fun `일반 회차 일시가 Expo UTC Instant로 와도 KST 예정 시각으로 출력한다`() {
        // Expo#196 이후 실제 응답 형식(#107)
        expo.on(
            "GET /internal/expo/$EVENT/standard-programs/7",
            body = mapOf("id" to 7, "title" to "골든벨", "startedAt" to "2026-10-31T01:00:00Z", "endedAt" to "2026-10-31T02:00:00Z"),
        )
        snapshot(listOf(app(1, participant = 1)))
        people(listOf(person(1, "가참가자", null)))
        attendance.on("GET /internal/program-attendances/standard/7", body = emptyList<Any>())

        val response = send("STANDARD", 7)

        response.status shouldBe 200
        XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { book ->
            book.getSheet("실제 출석 결과").values()[1].subList(0, 5) shouldBe
                listOf("일반", "7", "골든벨", "2026-10-31 10:00", "2026-10-31 11:00")
        }
    }

    @Test
    fun `신청과 출석이 모두 없으면 머리글만 있는 두 시트다`() {
        snapshot(emptyList())
        people(emptyList())
        attendance.on("GET /internal/program-attendances/standard/7", body = emptyList<Any>())

        val response = send("STANDARD", 7)

        response.status shouldBe 200
        XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { book ->
            book.getSheet("실제 출석 결과").values() shouldBe listOf(HEADERS)
            book.getSheet("신청 불일치 출석").values() shouldBe listOf(HEADERS)
        }
    }

    @Test
    fun `출석 원천 실패·날짜 누락·중복·유형 불일치는 전원 미출석 파일 대신 502다`() {
        snapshot(listOf(app(1, participant = 1)))
        people(listOf(person(1, "가참가자", null)))
        val cases =
            listOf(
                500 to """{"status":500}""",
                503 to """{"status":503}""",
                // #194 이전 공급자: attendanceDate 없음
                200 to """[{"participantId":1,"entryTime":"10:00","leaveTime":null}]""",
                200 to """[{"participantId":1,"attendanceDate":"2026-10-31","entryTime":"10:00"},""" +
                    """{"participantId":1,"attendanceDate":"2026-10-31","entryTime":"10:01"}]""",
                // 일반 회차인데 연수자 ID만 온 응답
                200 to """[{"traineeId":1,"attendanceDate":"2026-10-31","entryTime":"10:00"}]""",
            )
        cases.forEach { (status, body) ->
            attendance.on("GET /internal/program-attendances/standard/7", status, body)
            val response = send("STANDARD", 7)
            response.status shouldBe 502
            response.contentType shouldContain "application/json"
        }
    }

    @Test
    fun `신청·유저 원천 실패도 502이고 다른 행사 신청은 거절한다`() {
        attendance.on("GET /internal/program-attendances/standard/7", body = emptyList<Any>())
        people(emptyList())
        application.on("POST $TARGET", 503, """{"status":503}""")
        send("STANDARD", 7).status shouldBe 502

        snapshot(listOf(app(1, participant = 1, event = OTHER_EVENT)))
        send("STANDARD", 7).status shouldBe 502

        snapshot(listOf(app(1, participant = 1)))
        user.on("GET /internal/expos/$EVENT/standard-participants/details?size=500", 500, """{"status":500}""")
        send("STANDARD", 7).status shouldBe 502
    }

    @Test
    fun `행사·회차 없음은 404, 잘못된 입력은 400, 권한 없음은 401·403이다`() {
        expo.on("GET /internal/expo/$EVENT/standard-programs/8", 404, """{"status":404}""")
        send("STANDARD", 8).status shouldBe 404
        expo.on("POST /internal/expo/$EVENT/training-programs/batch", 404, """{"status":404}""")
        send("TRAINING", 8).status shouldBe 404
        send("STANDARD", 0).status shouldBe 400
        send("OTHER", 7).status shouldBe 400
        mvc
            .perform(
                get("/excel/expos/$EVENT/program-attendances").header(HttpHeaders.AUTHORIZATION, "Bearer ${TestJwt.token("ROLE_ADMIN")}"),
            ).andReturn()
            .response.status shouldBe 400
        send("STANDARD", 7, token = null).status shouldBe 401
        send("STANDARD", 7, token = TestJwt.token("ROLE_USER")).status shouldBe 403
        expo.on("GET /internal/expo/$EVENT/admin-access", 403, """{"status":403}""")
        send("STANDARD", 7).status shouldBe 403
        // 권한 거절 뒤 원천을 읽지 않는다
        attendance.requests shouldBe emptyList()
    }

    @Test
    fun `출석 서비스 연결이 없으면 503이다`() {
        val error = assertThrows<ExpectedException> { AttendanceClient(null).programAttendances(SessionType.STANDARD, 7) }
        error.status shouldBe HttpStatus.SERVICE_UNAVAILABLE
    }

    private fun send(
        type: String,
        programId: Long,
        token: String? = TestJwt.token("ROLE_ADMIN"),
    ): MockHttpServletResponse =
        mvc
            .perform(
                get("/excel/expos/$EVENT/program-attendances")
                    .param("programType", type)
                    .param("programId", programId.toString())
                    .apply { token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") } },
            ).andReturn()
            .response

    private fun snapshot(items: List<Any>) =
        application.on(
            "POST $TARGET",
            body =
                mapOf(
                    "snapshotId" to id(100),
                    "snapshotAt" to Instant.parse("2026-01-01T00:00:00Z"),
                    "expiresAt" to Instant.parse("2099-01-01T00:00:00Z"),
                    "totalElements" to items.size,
                    "items" to items,
                    "nextCursor" to null,
                ),
        )

    private fun people(items: List<Any>) =
        user.on("GET /internal/expos/$EVENT/standard-participants/details?size=500", body = mapOf("items" to items, "nextCursor" to null))

    private fun id(n: Int) = UUID.fromString("00000000-0000-0000-0000-%012d".format(n))

    private fun app(
        n: Int,
        participant: Long,
        trainee: Long? = null,
        type: String = "STANDARD",
        program: Long = 7,
        status: String = "CONFIRMED",
        at: String = "2026-10-01T00:00:00Z",
        event: UUID = EVENT,
    ): Map<String, Any> =
        mapOf(
            "expoId" to event,
            "registrationSource" to mapOf("expoId" to event, "participantId" to participant),
            "application" to
                mapOf(
                    "applicationId" to id(n),
                    "participantId" to participant,
                    "traineeId" to trainee,
                    "programType" to type,
                    "programId" to program,
                    "groupId" to id(200),
                    "status" to status,
                    "createdAt" to Instant.parse(at),
                    "formId" to id(300),
                    "formVersion" to 1,
                    "definition" to mapOf("expoId" to event, "id" to program, "programType" to type, "title" to "회차"),
                    "questions" to emptyList<Any>(),
                    "answers" to emptyMap<String, Any>(),
                ),
        ).let { it + ("application" to (it["application"] as Map<*, *>).filterValues { value -> value != null }) }

    private fun record(
        participant: Long? = null,
        trainee: Long? = null,
        date: String,
        time: String,
    ) = mapOf("participantId" to participant, "traineeId" to trainee, "attendanceDate" to date, "entryTime" to time, "leaveTime" to null)

    private fun person(
        id: Long,
        name: String?,
        school: String?,
    ) = mapOf(
        "participantId" to id,
        "name" to name,
        "phoneNumber" to "010-0000-0000",
        "personalInformationStatus" to true,
        "applicationType" to "PRE",
        "information" to mapOf("answers" to emptyMap<String, Any>()),
        "surveyAnswer" to null,
        "region" to null,
        "school" to school,
    )

    private fun trainee(
        id: Long,
        name: String,
    ) = mapOf(
        "traineeId" to id,
        "participantId" to null,
        "name" to name,
        "trainingId" to null,
        "phoneNumber" to null,
        "applicationType" to "PRE",
        "information" to mapOf("answers" to emptyMap<String, Any>()),
        "school" to "연수학교",
    )

    @TestConfiguration
    class ClientConfig {
        @Bean
        fun applicationClient() = ApplicationClient(application.client())

        @Bean
        fun userClient() = UserClient(user.client())

        @Bean
        fun expoClient() = ExpoClient(expo.client())

        @Bean
        fun attendanceClient() = AttendanceClient(attendance.client())
    }

    companion object {
        private val application = StubProvider("application-test")
        private val user = StubProvider("user-test")
        private val expo = StubProvider("expo-test")
        private val attendance = StubProvider("attendance-test")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
        }
    }
}
