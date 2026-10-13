package team.startup.report.domain.excel

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import team.startup.report.domain.excel.presentation.ExcelController
import team.startup.report.domain.excel.service.impl.HttpReportSource
import team.startup.report.domain.excel.service.impl.ProgramParticipantInfoToExcelServiceImpl
import team.startup.report.domain.excel.service.impl.StandardParticipantInfoToExcelServiceImpl
import team.startup.report.domain.excel.service.impl.TraineeAttendanceToExcelServiceImpl
import team.startup.report.domain.excel.service.impl.TraineeInfoToExcelServiceImpl
import team.startup.report.global.client.ApplicationClient
import team.startup.report.global.client.ExpoClient
import team.startup.report.global.client.StubProvider
import team.startup.report.global.client.UserClient
import team.startup.report.global.security.ExcelSecurityConfig
import team.startup.report.support.TestJwt
import team.startup.report.support.assertDarkHeader
import team.startup.report.support.cell
import team.startup.report.support.values
import java.io.ByteArrayInputStream
import java.time.Duration

private const val USER_TOKEN = "it-user-internal-token"
private const val APPLICATION_TOKEN = "it-application-internal-token"
private const val EXPO_TOKEN = "it-expo-internal-token"
private val TOKENS = listOf(USER_TOKEN, APPLICATION_TOKEN, EXPO_TOKEN)

private const val EXPO = "GET /internal/expo/e1"
private const val STANDARD_PAGE = "GET /internal/expos/e1/standard-participants/details?size=500"
private const val TRAINEE_PAGE = "GET /internal/expos/e1/trainees/details?size=500"
private const val TRAINING_APPLICATIONS = "POST /internal/training-program-applications/trainees"
private const val TRAINING_BATCH = "POST /internal/expo/e1/training-programs/batch"
private const val STANDARD_PROGRAM = "GET /internal/expo/e1/standard-programs/7"
private const val STANDARD_APPLICATIONS = "GET /internal/standard-program-applications/program/7"
private const val BRIEFS = "POST /internal/standard-participants/details"
private const val TRAINEE_DETAIL = "GET /internal/trainees/3/details"

/**
 * 4개 다운로드를 컨트롤러 → 서비스 → 실제 HttpReportSource·공급자 클라이언트 → HTTP 공급자 대역까지 연결해 XLSX와 v1 실패 계약을 비교한다.
 * 공급자 요청 형식·커서·분할 상한·순서 재조합 자체는 HttpReportSourceTests, 셀 서식 전체는 ExcelRenderersTests가 맡는다.
 */
@WebMvcTest(ExcelController::class)
@Import(
    ExcelSecurityConfig::class,
    TraineeInfoToExcelServiceImpl::class,
    StandardParticipantInfoToExcelServiceImpl::class,
    ProgramParticipantInfoToExcelServiceImpl::class,
    TraineeAttendanceToExcelServiceImpl::class,
    HttpReportSource::class,
    ExcelSourceIntegrationTests.StubClients::class,
)
@ExtendWith(OutputCaptureExtension::class)
class ExcelSourceIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun reset() {
        listOf(user, application, expo).forEach {
            it.routes.clear()
            synchronized(it.requests) { it.requests.clear() }
        }
        expo.on(
            "GET /internal/expo/e1/admin-access",
            body =
                mapOf(
                    "expoId" to "e1",
                    "adminId" to 1,
                    "permission" to "APPLICATION_READ",
                    "policyVersion" to "GLOBAL_ADMIN_APPLICATION_READ_V1",
                    "scope" to "GLOBAL_ADMIN",
                ),
        )
    }

    @Test
    fun `일반 참가자 - 커서 두 페이지를 ID 순으로 합치고 스냅샷으로 답변을 복원한다`(output: CapturedOutput) {
        expo.on(EXPO, body = expoBody())
        user.on(
            STANDARD_PAGE,
            body = page(listOf(standard(5, false, "FIELD", plain("학교명" to "나초"), survey(mapOf("s1" to "opt1"), null)), standard2()), 5),
        )
        user.on("$STANDARD_PAGE&cursor=5", body = page(listOf(standard(7, true, "PRE", plain("학교명" to "다초"), null)), null))

        val result = admin("/excel/standard/e1")

        result.response.status shouldBe 200
        result.response.getHeader(HttpHeaders.CONTENT_DISPOSITION) shouldBe "attachment; filename*=UTF-8''Participant_Information.xlsx"
        val sheet = result.sheet("박람회 참가자 정보")
        sheet.values() shouldBe
            listOf(
                listOf("이름", "전화번호", "개인정보 동의 여부", "신청 방식", "학교명", "관심분야", "만족도", "재방문"),
                listOf("참가자2", "010-0000-0002", "동의", "사전 등록", "가초", "AI, 로 봇", "좋음", "true"),
                listOf("참가자5", "010-0000-0005", "미동의", "현장 등록", "나초", "", "", ""),
                listOf("참가자7", "010-0000-0007", "동의", "사전 등록", "다초", "", "", ""),
            )
        sheet.cell(0, 7).assertDarkHeader()
        user.requests.map { it.uri } shouldContainExactly
            listOf(
                "/internal/expos/e1/standard-participants/details?size=500",
                "/internal/expos/e1/standard-participants/details?size=500&cursor=5",
            )
        assertProviderTokens()
        // 스냅샷 없는 설문 답변은 열을 만들지 않고 건수만 남긴다(개인정보 없음)
        output.out shouldContain "엑셀 답변 복원 불가: excel=standard, expoId=e1, count=1"
        output.out shouldNotContain "010-0000-0005"
    }

    @Test
    fun `일반 참가자 - 행사 없음과 빈 행사는 v1 메시지`() {
        expo.on(EXPO, 404, """{"status":404,"message":"박람회를 찾을 수 없습니다."}""")
        admin("/excel/standard/e1").assertError(500, "엑셀 파일 생성 중 오류 발생: null")
        user.requests shouldBe emptyList()

        expo.on(EXPO, body = expoBody())
        user.on(STANDARD_PAGE, body = page(emptyList(), null))
        admin("/excel/standard/e1").assertError(500, "엑셀 파일 생성 중 오류 발생: 참가자 정보가 존재하지 않습니다.")
    }

    @Test
    fun `연수자 - 신청 순서 강연, 중복 제목, 스냅샷 선택지`() {
        expo.on(EXPO, body = expoBody())
        user.on(
            TRAINEE_PAGE,
            body =
                page(
                    listOf(
                        trainee(2, "FIELD", plain("경력" to "5년")),
                        trainee(
                            1,
                            "PRE",
                            info(
                                mapOf("과목" to listOf("m1", "m2"), "소속" to "가초"),
                                listOf(
                                    question("b", "과목", 2, "MULTIPLE", mapOf("m1" to "수학", "m2" to "과학")),
                                    question("a", "소속", 1, "SENTENCE"),
                                ),
                            ),
                        ),
                    ),
                    null,
                ),
        )
        // 공급자 응답 순서와 무관하게 applicationId 순: 특강 → 기조 강연 → AI → AI(중복)
        application.on(
            TRAINING_APPLICATIONS,
            body =
                listOf(
                    trainingApplication(13, 1, 102),
                    trainingApplication(11, 1, 101),
                    trainingApplication(10, 1, 104),
                    trainingApplication(12, 1, 103),
                ),
        )
        expo.on(
            TRAINING_BATCH,
            body =
                listOf(
                    trainingProgram(103, "AI", "CHOICE"),
                    trainingProgram(101, "기조 강연", "ESSENTIAL"),
                    trainingProgram(104, "특강", null),
                    trainingProgram(102, "AI", "CHOICE"),
                ),
        )

        val result = admin("/excel/e1")

        result.response.status shouldBe 200
        result.response.getHeader(HttpHeaders.CONTENT_DISPOSITION)!! shouldMatch
            Regex("attachment; filename=\"교원연수자_정보_\\d{8}_\\d{6}\\.xlsx\"")
        result.sheet("사전 교원연수자 정보").values() shouldBe
            listOf(
                listOf("이름", "연수원아이디", "전화번호", "신청방식", "소속", "과목", "경력", "공통 강연", "선택 강연"),
                listOf("연수자1", "T1", "010-1", "사전 등록", "가초", "수학, 과학", "", "기조 강연", "특강, AI"),
                listOf("연수자2", "T2", "010-2", "현장 등록", "", "", "5년", "", ""),
            )
        assertProviderTokens()
    }

    @Test
    fun `연수자 - null과 누락 필드도 ID 순서와 개인 전화번호 및 신청을 유지한다`() {
        expo.on(EXPO, body = expoBody())
        val linked =
            trainee(2, "PRE", plain()) +
                mapOf("trainingId" to null, "phoneNumber" to null, "notificationPhoneNumber" to "010-representative")
        val different = trainee(1, "FIELD", plain()) + mapOf("notificationPhoneNumber" to "010-representative")
        val missing = trainee(3, "PRE", plain()) - setOf("trainingId", "phoneNumber")
        user.on(TRAINEE_PAGE, body = page(listOf(linked, different), 2))
        user.on("$TRAINEE_PAGE&cursor=2", body = page(listOf(missing, trainee(4, "PRE", plain())), null))
        application.on(
            TRAINING_APPLICATIONS,
            body = listOf(trainingApplication(30, 3, 102), trainingApplication(20, 2, 102), trainingApplication(10, 2, 101)),
        )
        expo.on(TRAINING_BATCH, body = listOf(trainingProgram(102, "선택", "CHOICE"), trainingProgram(101, "공통", "ESSENTIAL")))

        val result = admin("/excel/e1")

        result.response.status shouldBe 200
        XSSFWorkbook(ByteArrayInputStream(result.response.contentAsByteArray)).use { workbook ->
            workbook.numberOfSheets shouldBe 1
            val sheet = workbook.getSheet("사전 교원연수자 정보")
            sheet.values() shouldBe
                listOf(
                    listOf("이름", "연수원아이디", "전화번호", "신청방식", "공통 강연", "선택 강연"),
                    listOf("연수자1", "T1", "010-1", "현장 등록", "", ""),
                    listOf("연수자2", "", "", "사전 등록", "공통", "선택"),
                    listOf("연수자3", "", "", "사전 등록", "", "선택"),
                    listOf("연수자4", "T4", "010-4", "사전 등록", "", ""),
                )
            sheet.cell(0, 2).assertDarkHeader()
            sheet.cell(2, 2).cellStyle.borderBottom shouldBe BorderStyle.THIN
            sheet.getColumnWidth(2) shouldBe 20 * 256
        }
        StubProvider.JSON
            .readTree(application.requests.single().body)["traineeIds"]
            .values()
            .map { it.asLong() } shouldBe
            listOf(1L, 2L, 3L, 4L)
        assertProviderTokens()
    }

    @Test
    fun `출석부 - null 또는 누락 연수 번호는 빈 셀이고 프로그램 시작일과 기존 서식을 유지한다`() {
        expo.on(EXPO, body = expoBody())
        application.on(TRAINING_APPLICATIONS, body = listOf(trainingApplication(1, 3, 101)))
        expo.on(TRAINING_BATCH, body = listOf(trainingProgram(101, "기조 강연", "ESSENTIAL", "2026-10-09 10:00", "2026-10-09 11:00")))
        val detail =
            mapOf(
                "traineeId" to 3,
                "expoId" to "e1",
                "name" to "연수자3",
                "trainingId" to null,
                "information" to plain("소속" to "광주초"),
            )
        listOf(detail, detail - "trainingId").forEach { response ->
            user.on(TRAINEE_DETAIL, body = response)

            val result = admin("/excel/trainee/3")

            result.response.status shouldBe 200
            result.response.getHeader(HttpHeaders.CONTENT_DISPOSITION) shouldBe "attachment; filename=\"Trainee_Attendance_연수자3.xlsx\""
            XSSFWorkbook(ByteArrayInputStream(result.response.contentAsByteArray)).use { workbook ->
                workbook.numberOfSheets shouldBe 1
                val sheet = workbook.getSheet("출석부")
                sheet.values()[2][2] shouldBe "2026.10.09.(금)"
                sheet.values()[8] shouldBe listOf("1", "광주초", "연수자3", null, null, null, null, null, null, null)
                DataFormatter().formatCellValue(sheet.cell(10, 9)) shouldBe ""
                sheet.cell(10, 9).cellStyle.borderBottom shouldBe BorderStyle.THIN
                sheet.getColumnWidth(9) shouldBe 3800
                sheet.mergedRegions.map { it.formatAsString() } shouldContainAll listOf("A1:J1", "D8:H8")
            }
        }
        assertProviderTokens()
    }

    @Test
    fun `연수자 - 행사 없음과 연수자 없음은 v1 메시지`() {
        expo.on(EXPO, 404, """{"status":404}""")
        admin("/excel/e1").assertError(500, "예상치 못한 오류 발생")

        expo.on(EXPO, body = expoBody())
        user.on(TRAINEE_PAGE, body = page(emptyList(), null))
        admin("/excel/e1").assertError(500, "예상치 못한 오류 발생")
        application.requests shouldBe emptyList()
    }

    @Test
    fun `프로그램 참가자 - 신청 순서, 0명은 머리글만, 소속 아님`() {
        expo.on(STANDARD_PROGRAM, body = mapOf("id" to 7, "title" to "부스 체험"))
        application.on(
            STANDARD_APPLICATIONS,
            body = listOf(standardApplication(30, 5), standardApplication(10, 9), standardApplication(20, 4)),
        )
        user.on(BRIEFS, body = listOf(brief(5, true), brief(4, false), brief(9, true)))

        val result = admin("/excel/program/e1?programId=7")

        result.response.status shouldBe 200
        result.response.getHeader(HttpHeaders.CONTENT_DISPOSITION) shouldBe "attachment; filename=\"Program_Participant_Information.xlsx\""
        result.sheet("프로그램 참가자 정보").values() shouldBe
            listOf(
                listOf("순위", "이름", "전화번호", "개인정보 동의 여부", "학번", "서명", "비고"),
                listOf("1.0", "참가자9", "010-9", "동의", "", "", ""),
                listOf("2.0", "참가자4", "010-4", "미동의", "", "", ""),
                listOf("3.0", "참가자5", "010-5", "동의", "", "", ""),
            )
        assertProviderTokens()

        application.on(STANDARD_APPLICATIONS, body = emptyList<Any>())
        user.requests.clear()
        val empty = admin("/excel/program/e1?programId=7")
        empty.response.status shouldBe 200
        empty.sheet("프로그램 참가자 정보").values() shouldBe listOf(listOf("순위", "이름", "전화번호", "개인정보 동의 여부", "학번", "서명", "비고"))
        user.requests shouldBe emptyList()

        expo.on(STANDARD_PROGRAM, 404, """{"status":404}""")
        application.requests.clear()
        admin("/excel/program/e1?programId=7").assertError(500, "엑셀 파일 생성 중 오류 발생")
        application.requests shouldBe emptyList()
    }

    @Test
    fun `출석부 - 신청 프로그램 시작일로 날짜 블록, 신청 순서 강연, 스냅샷 폼 값`() {
        user.on(
            TRAINEE_DETAIL,
            body =
                mapOf(
                    "traineeId" to 3,
                    "expoId" to "e1",
                    "name" to "홍길동",
                    "trainingId" to "TR-1",
                    "information" to
                        info(
                            mapOf("이름" to "홍길동", "학교명" to "광주초"),
                            listOf(question("q2", "이름", 2, "SENTENCE"), question("q1", "학교명", 1, "SENTENCE")),
                        ),
                ),
        )
        expo.on(EXPO, body = expoBody("2026 AI 박람회"))
        application.on(
            TRAINING_APPLICATIONS,
            body = listOf(trainingApplication(22, 3, 3), trainingApplication(20, 3, 1), trainingApplication(21, 3, 2)),
        )
        expo.on(
            TRAINING_BATCH,
            body =
                listOf(
                    trainingProgram(3, "코딩", "CHOICE", "2026-05-02 09:00", "2026-05-02 10:00"),
                    trainingProgram(2, "AI 교육", "CHOICE", "2026-05-01T13:00", "2026-05-01T14:30"),
                    trainingProgram(1, "기조 강연", "ESSENTIAL", "2026-05-01 10:00", "2026-05-01 11:00"),
                ),
        )

        val result = admin("/excel/trainee/3")

        result.response.status shouldBe 200
        result.response.getHeader(HttpHeaders.CONTENT_DISPOSITION) shouldBe "attachment; filename=\"Trainee_Attendance_홍길동.xlsx\""
        val sheet = result.sheet("출석부")
        val values = sheet.values()
        values[0] shouldBe listOf("출  석  부")
        values[1][2]!! shouldStartWith "2026 AI 박람회 직무연수"
        values[2] shouldBe listOf("연수일시", null, "2026.05.01.(금)/05.02.(토) 해당일 입력")
        values[5][3] shouldBe "05.01.(금)"
        values[9][3] shouldBe "05.02.(토)"
        listOf(7, 11).forEach {
            values[it].subList(3, 6) shouldBe listOf("기조 강연\n(10:00~11:00)", "AI 교육\n(13:00~14:30)", "코딩\n(09:00~10:00)")
        }
        listOf(8, 12).forEach { values[it] shouldBe listOf("1", "광주초", "홍길동", null, null, null, null, null, null, "TR-1") }
        sheet.mergedRegions.map { it.formatAsString() } shouldContainAll listOf("A1:J1", "D8:H8", "D13:H13")
        assertProviderTokens()
    }

    @Test
    fun `출석부 - 없는 연수자와 신청 없음은 v1 메시지`() {
        user.on(TRAINEE_DETAIL, 404, """{"status":404}""")
        admin("/excel/trainee/3").assertError(500, "해당 연수자를 찾을 수 없습니다.")

        user.on(
            TRAINEE_DETAIL,
            body =
                mapOf(
                    "traineeId" to 3,
                    "expoId" to "e1",
                    "name" to "홍길동",
                    "trainingId" to "TR-1",
                    "information" to plain(),
                ),
        )
        expo.on(EXPO, body = expoBody())
        application.on(TRAINING_APPLICATIONS, body = emptyList<Any>())
        admin("/excel/trainee/3").assertError(500, "해당 연수자가 신청한 프로그램이 없습니다.")
        expo.requests.filterNot { it.uri.endsWith("/admin-access") }.map { it.method } shouldBe listOf("GET")
    }

    @Test
    fun `공급자 장애는 엔드포인트별 v1 500이고 토큰·주소·공급자 본문을 노출하지 않는다`(output: CapturedOutput) {
        expo.on(EXPO, body = expoBody())
        listOf(401, 403, 500, 503).forEach { status ->
            user.on(STANDARD_PAGE, status, """{"status":$status,"message":"secret-body"}""")
            admin("/excel/standard/e1").assertError(500, "엑셀 파일 생성 중 오류 발생: User 서비스 응답 오류(HTTP $status)")
        }
        user.routes[STANDARD_PAGE] = { StubProvider.Reply(200, page(emptyList(), null).toJson(), Duration.ofMillis(1_500)) }
        admin("/excel/standard/e1").assertError(500, "엑셀 파일 생성 중 오류 발생: User 서비스 연결 실패")
        user.on(STANDARD_PAGE, body = """{"items":[{"participantId":"secret-body"}]""")
        admin("/excel/standard/e1").assertError(500, "엑셀 파일 생성 중 오류 발생: User 서비스 응답 형식 오류")

        // 연수 프로그램 일괄 조회 404(섞인 ID)는 대상 없음이 아니라 실패
        user.on(TRAINEE_PAGE, body = page(listOf(trainee(1, "PRE", plain())), null))
        application.on(TRAINING_APPLICATIONS, body = listOf(trainingApplication(1, 1, 101)))
        expo.on(TRAINING_BATCH, 404, """{"status":404,"message":"secret-body"}""")
        admin("/excel/e1").assertError(500, "예상치 못한 오류 발생")

        expo.on(STANDARD_PROGRAM, body = mapOf("id" to 7, "title" to "부스"))
        application.on(STANDARD_APPLICATIONS, 503, "secret-body")
        admin("/excel/program/e1?programId=7").assertError(500, "엑셀 파일 생성 중 오류 발생")

        user.on(TRAINEE_DETAIL, 500, "secret-body")
        admin("/excel/trainee/3").assertError(502, "연수자의 행사 정보를 확인할 수 없습니다.")

        TOKENS.forEach { output.all shouldNotContain it }
        output.all shouldNotContain "secret-body"
    }

    @Test
    fun `같은 문항 ID의 서로 다른 제출 버전은 각 스냅샷 표시문구를 사용한다`() {
        expo.on(EXPO, body = expoBody())

        fun version(label: String) =
            info(
                mapOf("선택" to "key"),
                listOf(question("q1", "선택", 1, "DROPDOWN", mapOf("key" to label))),
            )
        user.on(
            STANDARD_PAGE,
            body = page(listOf(standard(1, true, "PRE", version("이전 보기"), null), standard(2, true, "PRE", version("변경 보기"), null)), null),
        )
        val sheet = admin("/excel/standard/e1").sheet("박람회 참가자 정보")
        sheet.cell(1, 4).stringCellValue shouldBe "이전 보기"
        sheet.cell(2, 4).stringCellValue shouldBe "변경 보기"
        sheet.getRow(0).lastCellNum.toInt() shouldBe 5
    }

    @Test
    fun `네 양식의 수식 모양 외부 입력은 실제 XLSX 문자열 셀이다`() {
        val payloads = listOf("=HYPERLINK(\"https://example.invalid\",\"x\")", "+1+1", "-1+1", "@SUM(A1:A2)")
        expo.on(EXPO, body = expoBody())
        user.on(
            STANDARD_PAGE,
            body =
                page(
                    payloads.mapIndexed {
                        index,
                        text,
                        ->
                        standard(index + 1L, true, "PRE", plain("입력" to text), null)
                    },
                    null,
                ),
        )
        user.on(TRAINEE_PAGE, body = page(payloads.mapIndexed { index, text -> trainee(index + 1L, "PRE", plain("입력" to text)) }, null))
        application.on(TRAINING_APPLICATIONS, body = emptyList<Any>())
        expo.on(STANDARD_PROGRAM, body = mapOf("id" to 7, "title" to "부스"))
        application.on(STANDARD_APPLICATIONS, body = payloads.indices.map { standardApplication(it + 1L, it + 1L) })
        user.on(BRIEFS, body = payloads.mapIndexed { index, text -> brief(index + 1L, true) + mapOf("name" to text) })
        user.on(
            TRAINEE_DETAIL,
            body =
                mapOf(
                    "traineeId" to 3,
                    "expoId" to "e1",
                    "name" to payloads[0],
                    "trainingId" to payloads[1],
                    "information" to plain("소속" to payloads[2]),
                ),
        )
        val standardSheet = admin("/excel/standard/e1").sheet("박람회 참가자 정보")
        val traineeSheet = admin("/excel/e1").sheet("사전 교원연수자 정보")
        val programSheet = admin("/excel/program/e1?programId=7").sheet("프로그램 참가자 정보")
        payloads.forEachIndexed { index, text ->
            listOf(standardSheet.cell(index + 1, 4), traineeSheet.cell(index + 1, 4), programSheet.cell(index + 1, 1)).forEach {
                it.cellType shouldBe CellType.STRING
                it.stringCellValue shouldBe text
            }
        }
        application.on(TRAINING_APPLICATIONS, body = listOf(trainingApplication(1, 3, 1)))
        expo.on(TRAINING_BATCH, body = listOf(trainingProgram(1, payloads[3], "ESSENTIAL")))
        val attendance = admin("/excel/trainee/3").sheet("출석부")
        val cells = attendance.flatMap { it.toList() }
        cells.any { it.cellType == CellType.FORMULA } shouldBe false
        payloads.forEach { text -> cells.any { it.cellType == CellType.STRING && it.stringCellValue.contains(text) } shouldBe true }
    }

    @Test
    fun `큰 연수자 목록은 커서 전체와 500명 및 100개 배치를 실제 파일에 반영한다`() {
        expo.on(EXPO, body = expoBody())
        user.on(TRAINEE_PAGE, body = page((1L..500L).map { trainee(it, "PRE", plain()) }, 500))
        user.on("$TRAINEE_PAGE&cursor=500", body = page((501L..1001L).take(500).map { trainee(it, "PRE", plain()) }, 1000))
        user.on("$TRAINEE_PAGE&cursor=1000", body = page(listOf(trainee(1001, "PRE", plain())), null))
        application.routes[TRAINING_APPLICATIONS] = { request ->
            val ids =
                StubProvider.JSON
                    .readTree(request.body)["traineeIds"]
                    .values()
                    .map { it.asLong() }
            StubProvider.Reply(200, ids.map { trainingApplication(it, it, it) }.toJson())
        }
        expo.routes[TRAINING_BATCH] = { request ->
            val ids =
                StubProvider.JSON
                    .readTree(request.body)["programIds"]
                    .values()
                    .map { it.asLong() }
            StubProvider.Reply(200, ids.map { trainingProgram(it, "강연$it", "ESSENTIAL") }.toJson())
        }
        val sheet = admin("/excel/e1").sheet("사전 교원연수자 정보")
        sheet.lastRowNum shouldBe 1001
        (1..1001).forEach { id ->
            sheet.cell(id, 0).stringCellValue shouldBe "연수자$id"
            sheet.cell(id, 4).stringCellValue shouldBe "강연$id"
        }
        application.requests.map { StubProvider.JSON.readTree(it.body)["traineeIds"].size() } shouldContainExactly listOf(500, 500, 1)
        expo.requests.filter { it.method == "POST" }.map { StubProvider.JSON.readTree(it.body)["programIds"].size() } shouldContainExactly
            (List(10) { 100 } + 1)
    }

    @Test
    fun `원천 일부 누락과 뒤 페이지 장애는 부분 XLSX 성공으로 위장하지 않는다`() {
        expo.on(EXPO, body = expoBody())
        user.on(STANDARD_PAGE, body = page(listOf(standard2()), 2))
        user.on("$STANDARD_PAGE&cursor=2", 503, "secret-body")
        admin("/excel/standard/e1").assertError(500, "엑셀 파일 생성 중 오류 발생: User 서비스 응답 오류(HTTP 503)")
        expo.on(STANDARD_PROGRAM, body = mapOf("id" to 7, "title" to "부스"))
        application.on(STANDARD_APPLICATIONS, body = listOf(standardApplication(1, 1), standardApplication(2, 2)))
        user.on(BRIEFS, body = listOf(brief(1, true)))
        admin("/excel/program/e1?programId=7").assertError(500, "엑셀 파일 생성 중 오류 발생")
        user.on(TRAINEE_PAGE, body = page(listOf(trainee(1, "PRE", plain())), null))
        application.on(TRAINING_APPLICATIONS, body = listOf(trainingApplication(1, 1, 1)))
        expo.on(TRAINING_BATCH, body = emptyList<Any>())
        admin("/excel/e1").assertError(500, "예상치 못한 오류 발생")
    }

    @Test
    fun `다른 연수자의 단건 및 신청 응답으로 출석부를 만들지 않는다`() {
        expo.on(EXPO, body = expoBody())
        user.on(
            TRAINEE_DETAIL,
            body = mapOf("traineeId" to 4, "expoId" to "e1", "name" to "다른 사람", "trainingId" to "T4", "information" to plain()),
        )
        application.on(TRAINING_APPLICATIONS, body = listOf(trainingApplication(1, 4, 1)))
        expo.on(TRAINING_BATCH, body = listOf(trainingProgram(1, "다른 사람 강연", "ESSENTIAL")))
        admin("/excel/trainee/3").assertError(502, "연수자의 행사 정보를 확인할 수 없습니다.")
        application.requests shouldBe emptyList()

        user.on(
            TRAINEE_DETAIL,
            body = mapOf("traineeId" to 3, "expoId" to "e1", "name" to "본인", "trainingId" to "T3", "information" to plain()),
        )
        admin("/excel/trainee/3").assertError(500, "출석부 엑셀 생성 중 오류 발생")
        expo.requests.none { it.method == "POST" } shouldBe true
    }

    @Test
    fun `등록 원천 - 구형 본인 동행자 타 행사 응답을 XLSX에서 분리한다`() {
        expo.on(EXPO, body = expoBody())
        application.on(TRAINING_APPLICATIONS, body = emptyList<Any>())
        val registration =
            info(
                mapOf("과목" to listOf("m1", "m2"), "소속" to "등록학교"),
                listOf(question("b", "과목", 2, "MULTIPLE", mapOf("m1" to "수학", "m2" to "과학")), question("a", "소속", 1, "SENTENCE")),
            )
        val linked =
            trainee(2, "PRE", plain()) +
                mapOf(
                    "participantId" to 20,
                    "school" to "fallback학교",
                    "registration" to registration,
                    "trainingId" to null,
                    "phoneNumber" to null,
                    "notificationPhoneNumber" to "대표수신번호",
                )
        val companion = trainee(3, "PRE", plain()) + mapOf("participantId" to 30, "school" to "동행학교", "registration" to null)
        val otherExpo = trainee(4, "PRE", plain()) + mapOf("participantId" to 40, "registration" to null)
        val unlinked = trainee(5, "PRE", plain()) + mapOf("registration" to registration)
        user.on(TRAINEE_PAGE, body = page(listOf(trainee(1, "FIELD", plain("소속" to "구형학교")), linked), 2))
        user.on("$TRAINEE_PAGE&cursor=2", body = page(listOf(companion, otherExpo, unlinked), null))

        val result = admin("/excel/e1")

        result.response.status shouldBe 200
        XSSFWorkbook(ByteArrayInputStream(result.response.contentAsByteArray)).use { workbook ->
            workbook.numberOfSheets shouldBe 1
            workbook.getSheetAt(0).values() shouldBe
                listOf(
                    listOf("이름", "연수원아이디", "전화번호", "신청방식", "소속", "과목", "공통 강연", "선택 강연"),
                    listOf("연수자1", "T1", "010-1", "현장 등록", "구형학교", "", "", ""),
                    listOf("연수자2", "", "", "사전 등록", "등록학교", "수학, 과학", "", ""),
                    listOf("연수자3", "T3", "010-3", "사전 등록", "동행학교", "", "", ""),
                    listOf("연수자4", "T4", "010-4", "사전 등록", "", "", "", ""),
                    listOf("연수자5", "T5", "010-5", "사전 등록", "", "", "", ""),
                )
        }
        user.on(TRAINEE_PAGE, body = page(listOf(companion), null))
        val schoolOnly = admin("/excel/e1")
        XSSFWorkbook(ByteArrayInputStream(schoolOnly.response.contentAsByteArray)).use { workbook ->
            workbook.getSheetAt(0).values()[0] shouldBe listOf("이름", "연수원아이디", "전화번호", "신청방식", "공통 강연", "선택 강연")
        }
        assertProviderTokens()
    }

    @Test
    fun `출석부 - 학교명 소속 school 순서와 기존 개인 식별값을 유지한다`() {
        expo.on(EXPO, body = expoBody())
        application.on(TRAINING_APPLICATIONS, body = listOf(trainingApplication(1, 3, 101)))
        expo.on(TRAINING_BATCH, body = listOf(trainingProgram(101, "기조 강연", "ESSENTIAL", "2026-10-09 10:00", "2026-10-09 11:00")))
        val registration = plain("소속" to "등록소속", "이름" to "등록이름", "연수원아이디" to "등록ID")
        val detail =
            mapOf(
                "traineeId" to 3,
                "expoId" to "e1",
                "name" to "본인이름",
                "trainingId" to null,
                "information" to plain(),
                "participantId" to 30,
                "school" to "fallback학교",
                "registration" to registration,
            )
        val cases =
            listOf(
                detail to "등록소속",
                (detail + mapOf("information" to plain("소속" to "기존소속"))) to "기존소속",
                (detail + mapOf("information" to plain("학교명" to "기존학교", "소속" to "기존소속"))) to "기존학교",
                (detail + mapOf("registration" to null)) to "fallback학교",
                (detail - setOf("participantId", "school", "registration")) to "학교명",
            )
        cases.forEach { (response, expectedSchool) ->
            user.on(TRAINEE_DETAIL, body = response)
            val result = admin("/excel/trainee/3")
            result.response.status shouldBe 200
            XSSFWorkbook(ByteArrayInputStream(result.response.contentAsByteArray)).use { workbook ->
                val sheet = workbook.getSheetAt(0)
                sheet.values()[8][1] shouldBe expectedSchool
                sheet.values()[8][2] shouldBe "본인이름"
                DataFormatter().formatCellValue(sheet.cell(10, 9)) shouldBe ""
                sheet.values()[2][2] shouldBe "2026.10.09.(금)"
            }
        }
        assertProviderTokens()
    }

    private fun admin(path: String): MvcResult =
        mockMvc
            .perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer ${TestJwt.token("ROLE_ADMIN")}"))
            .andReturn()

    private fun MvcResult.assertError(
        status: Int,
        message: String,
    ) {
        response.status shouldBe status
        response.contentType!! shouldStartWith "application/json"
        val body = response.getContentAsString(Charsets.UTF_8)
        body shouldBe """{"status":$status,"message":"$message"}"""
        (TOKENS + "127.0.0.1" + "secret-body").forEach { body shouldNotContain it }
    }

    private fun MvcResult.sheet(name: String): Sheet = XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).getSheet(name)

    /** 모든 요청이 그 공급자의 토큰만 실었고 URL에 토큰이 없다 */
    private fun assertProviderTokens() {
        mapOf(user to USER_TOKEN, application to APPLICATION_TOKEN, expo to EXPO_TOKEN).forEach { (stub, token) ->
            stub.requests.forEach {
                it.token shouldBe token
                TOKENS.forEach { t -> it.uri shouldNotContain t }
            }
        }
    }

    @TestConfiguration
    class StubClients {
        @Bean
        fun userClient() = UserClient(user.client(READ_TIMEOUT))

        @Bean
        fun applicationClient() = ApplicationClient(application.client(READ_TIMEOUT))

        @Bean
        fun expoClient() = ExpoClient(expo.client(READ_TIMEOUT))
    }

    companion object {
        private val READ_TIMEOUT = Duration.ofSeconds(1)
        val user = StubProvider(USER_TOKEN)
        val application = StubProvider(APPLICATION_TOKEN)
        val expo = StubProvider(EXPO_TOKEN)

        @JvmStatic
        @AfterAll
        fun close() = listOf(user, application, expo).forEach(StubProvider::close)

        @JvmStatic
        @DynamicPropertySource
        fun jwt(registry: DynamicPropertyRegistry) {
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
        }
    }
}

private fun Any.toJson(): String = StubProvider.JSON.writeValueAsString(this)

private fun expoBody(title: String = "박람회") = mapOf("title" to title, "startedDay" to "2026-05-01", "finishedDay" to "2026-05-02")

private fun page(
    items: List<Any>,
    nextCursor: Long?,
) = mapOf("items" to items, "nextCursor" to nextCursor)

private fun info(
    answers: Map<String, Any?>,
    questions: List<Any>?,
) = mapOf("answers" to answers, "questions" to questions)

/** 스냅샷 없이 저장된 신청 답변(제목 키) */
private fun plain(vararg answers: Pair<String, Any?>) = info(mapOf(*answers), null)

private fun survey(
    answers: Map<String, Any?>,
    questions: List<Any>?,
) = info(answers, questions)

private fun question(
    id: String,
    title: String,
    order: Int,
    formType: String,
    jsonData: Map<String, Any>? = null,
) = mapOf("id" to id, "title" to title, "order" to order, "formType" to formType, "jsonData" to jsonData)

private fun standard(
    id: Long,
    agreed: Boolean,
    applicationType: String,
    information: Any,
    surveyAnswer: Any?,
) = mapOf(
    "participantId" to id,
    "name" to "참가자$id",
    "phoneNumber" to "010-0000-000$id",
    "personalInformationStatus" to agreed,
    "applicationType" to applicationType,
    "information" to information,
    "surveyAnswer" to surveyAnswer,
)

/** 신청 폼은 MULTIPLE 키 배열, 설문은 문항 ID 키(DROPDOWN 키, CHECKBOX boolean) */
private fun standard2() =
    standard(
        2,
        true,
        "PRE",
        info(
            mapOf("관심분야" to listOf("k1", "k2"), "학교명" to "가초"),
            listOf(
                question("q1", "관심분야", 2, "MULTIPLE", mapOf("k1" to "AI", "k2" to mapOf("value" to "로\n봇"))),
                question("q0", "학교명", 1, "SENTENCE"),
            ),
        ),
        survey(
            mapOf("s2" to true, "s1" to "opt2"),
            listOf(
                question("s1", "만족도", 1, "DROPDOWN", mapOf("opt1" to "보통", "opt2" to "좋음")),
                question("s2", "재방문", 2, "CHECKBOX"),
            ),
        ),
    )

private fun trainee(
    id: Long,
    applicationType: String,
    information: Any,
) = mapOf(
    "traineeId" to id,
    "name" to "연수자$id",
    "trainingId" to "T$id",
    "phoneNumber" to "010-$id",
    "personalInformationStatus" to true,
    "applicationType" to applicationType,
    "information" to information,
)

private fun trainingApplication(
    applicationId: Long,
    traineeId: Long,
    programId: Long,
) = mapOf("applicationId" to applicationId, "traineeId" to traineeId, "trainingProgramId" to programId)

private fun trainingProgram(
    id: Long,
    title: String,
    category: String?,
    startedAt: String = "2026-05-01 10:00",
    endedAt: String = "2026-05-01 11:00",
) = mapOf("id" to id, "title" to title, "startedAt" to startedAt, "endedAt" to endedAt, "category" to category)

private fun standardApplication(
    applicationId: Long,
    participantId: Long,
) = mapOf("applicationId" to applicationId, "participantId" to participantId, "status" to false, "entryTime" to null, "leaveTime" to null)

private fun brief(
    id: Long,
    agreed: Boolean,
) = mapOf("participantId" to id, "name" to "참가자$id", "phoneNumber" to "010-$id", "personalInformationStatus" to agreed)
