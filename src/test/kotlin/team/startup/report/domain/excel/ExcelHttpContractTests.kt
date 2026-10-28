package team.startup.report.domain.excel

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import team.startup.report.domain.excel.presentation.controller.ExcelController
import team.startup.report.domain.excel.service.ApplicationType
import team.startup.report.domain.excel.service.ProgramParticipant
import team.startup.report.domain.excel.service.ReportSource
import team.startup.report.domain.excel.service.StandardParticipant
import team.startup.report.domain.excel.service.Trainee
import team.startup.report.domain.excel.service.TraineeAttendance
import team.startup.report.domain.excel.service.TrainingCategory
import team.startup.report.domain.excel.service.TrainingProgram
import team.startup.report.domain.excel.service.impl.ProgramParticipantInfoToExcelServiceImpl
import team.startup.report.domain.excel.service.impl.StandardParticipantInfoToExcelServiceImpl
import team.startup.report.domain.excel.service.impl.TraineeAttendanceToExcelServiceImpl
import team.startup.report.domain.excel.service.impl.TraineeInfoToExcelServiceImpl
import team.startup.report.global.client.ExpoClient
import team.startup.report.global.client.InformationResDto
import team.startup.report.global.client.StubProvider
import team.startup.report.global.client.TraineeDetail
import team.startup.report.global.client.UserClient
import team.startup.report.global.security.ExcelSecurityConfig
import team.startup.report.support.TestJwt
import java.io.ByteArrayInputStream

private const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/** v1 HTTP 계약: 경로·파라미터·상태코드·헤더·실패 본문 {"status","message"} */
@WebMvcTest(ExcelController::class)
@Import(
    ExcelSecurityConfig::class,
    TraineeInfoToExcelServiceImpl::class,
    StandardParticipantInfoToExcelServiceImpl::class,
    ProgramParticipantInfoToExcelServiceImpl::class,
    TraineeAttendanceToExcelServiceImpl::class,
    ExcelHttpContractTests.FakeSourceConfig::class,
)
class ExcelHttpContractTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var source: FakeReportSource

    @BeforeEach
    fun reset() = source.reset()

    @Test
    fun `일반 참가자 성공 - v1 헤더와 시트`() {
        source.standard = listOf(StandardParticipant("가", "010", true, ApplicationType.PRE, emptyMap(), emptyMap()))
        val result = admin("/excel/standard/expo-1")
        result.response.status shouldBe 200
        result.response.contentType shouldBe XLSX
        result.response.getHeader(HttpHeaders.CONTENT_DISPOSITION) shouldBe "attachment; filename*=UTF-8''Participant_Information.xlsx"
        result.sheetName() shouldBe "박람회 참가자 정보"
        source.calls shouldBe listOf("standard:expo-1")
    }

    @Test
    fun `일반 참가자 대상 없음은 404`() {
        source.standard = null
        admin("/excel/standard/x").assertError(404, "박람회를 찾을 수 없습니다.")
        source.standard = emptyList()
        admin("/excel/standard/x").assertError(404, "참가자 정보가 존재하지 않습니다.")
    }

    @Test
    fun `연수자 성공과 실패`() {
        source.trainees = listOf(Trainee("가", "T", "010", ApplicationType.PRE, emptyMap(), emptyList()))
        val result = admin("/excel/expo-1")
        result.response.status shouldBe 200
        result.response.contentType shouldBe XLSX
        result.response.getHeader(HttpHeaders.CONTENT_DISPOSITION)!! shouldMatch
            Regex("attachment; filename\\*=UTF-8''%EA%B5%90%EC%9B%90%EC%97%B0%EC%88%98%EC%9E%90_%EC%A0%95%EB%B3%B4_\\d{8}_\\d{6}\\.xlsx")
        result.sheetName() shouldBe "사전 교원연수자 정보"

        source.trainees = null
        admin("/excel/expo-1").assertError(404, "박람회를 찾을 수 없습니다.")
        source.trainees = emptyList()
        admin("/excel/expo-1").assertError(404, "연수자가 존재하지 않습니다.")
    }

    @Test
    fun `프로그램 참가자 - 0명도 성공, 소속 아님은 404`() {
        source.program = emptyList()
        val result = admin("/excel/program/expo-1?programId=7")
        result.response.status shouldBe 200
        result.response.getHeader(HttpHeaders.CONTENT_DISPOSITION) shouldBe "attachment; filename=\"Program_Participant_Information.xlsx\""
        result.sheetName() shouldBe "프로그램 참가자 정보"
        source.calls shouldBe listOf("program:expo-1:7")

        source.program = null
        admin("/excel/program/expo-1?programId=7").assertError(404, "프로그램을 찾을 수 없습니다.")
    }

    @Test
    fun `프로그램 참가자 - 파라미터 누락과 숫자 아님은 400`() {
        admin("/excel/program/expo-1").response.status shouldBe 400
        admin("/excel/program/expo-1?programId=abc").assertError(400, "잘못된 요청입니다.")
        source.calls shouldBe emptyList()
    }

    @Test
    fun `출석부 성공과 실패`() {
        source.attendance =
            TraineeAttendance(
                "박람회",
                "홍길동",
                "T",
                emptyMap(),
                listOf(TrainingProgram(1, "기조 강연", TrainingCategory.ESSENTIAL, "2026-05-01 10:00", "2026-05-01 11:00")),
            )
        val result = admin("/excel/trainee/3")
        result.response.status shouldBe 200
        result.response.getHeader(HttpHeaders.CONTENT_DISPOSITION) shouldBe
            "attachment; filename*=UTF-8''Trainee_Attendance_%ED%99%8D%EA%B8%B8%EB%8F%99.xlsx"
        result.sheetName() shouldBe "출석부"
        source.calls shouldBe listOf("attendance:3")

        source.attendance = source.attendance!!.copy(programs = emptyList())
        admin("/excel/trainee/3").assertError(404, "해당 연수자가 신청한 프로그램이 없습니다.")
        source.attendance = null
        admin("/excel/trainee/3").assertError(404, "해당 연수자를 찾을 수 없습니다.")
        admin("/excel/trainee/abc").assertError(400, "잘못된 요청입니다.")
    }

    @Test
    fun `원천 연동 실패는 가짜 파일 없이 v1 실패 형식`() {
        source.failure = IllegalStateException("리포트 원천 데이터 연동이 아직 준비되지 않았습니다.")
        admin("/excel/standard/x").assertError(500, "엑셀 파일 생성 중 오류 발생: 리포트 원천 데이터 연동이 아직 준비되지 않았습니다.")
        admin("/excel/x").assertError(500, "예상치 못한 오류 발생")
        admin("/excel/program/x?programId=1").assertError(500, "엑셀 파일 생성 중 오류 발생")
        admin("/excel/trainee/1").assertError(500, "출석부 엑셀 생성 중 오류 발생")
    }

    @Test
    fun `관리자만 허용`() {
        perform("/excel/x", null).assertError(401, "인증이 필요합니다.")
        perform("/excel/x", TestJwt.token("ROLE_ADMIN", signedByOtherKey = true)).assertError(401, "인증이 필요합니다.")
        perform("/excel/x", TestJwt.token("ROLE_ADMIN", ttlSeconds = 3600)).assertError(401, "인증이 필요합니다.")
        perform("/excel/x", TestJwt.token("ROLE_STANDARD")).assertError(403, "접근 권한이 없습니다.")
        mockMvc
            .perform(post("/excel/x").header(HttpHeaders.AUTHORIZATION, "Bearer ${TestJwt.token("ROLE_ADMIN")}"))
            .andReturn()
            .assertError(403, "접근 권한이 없습니다.")
        // Gateway가 넣는 사용자 헤더는 신뢰하지 않는다
        mockMvc
            .perform(get("/excel/x").header("X-User-Id", "1").header("X-User-Role", "ROLE_ADMIN"))
            .andReturn()
            .assertError(401, "인증이 필요합니다.")
        source.calls shouldBe emptyList()
    }

    private fun admin(path: String) = perform(path, TestJwt.token("ROLE_ADMIN"))

    private fun perform(
        path: String,
        token: String?,
    ): MvcResult =
        mockMvc
            .perform(get(path).apply { token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") } })
            .andReturn()

    private fun MvcResult.assertError(
        status: Int,
        message: String,
    ) {
        response.status shouldBe status
        response.contentType!! shouldStartWith "application/json"
        response.getContentAsString(Charsets.UTF_8) shouldBe """{"status":$status,"message":"$message"}"""
    }

    private fun MvcResult.sheetName() = XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { it.getSheetAt(0).sheetName }

    @TestConfiguration
    class FakeSourceConfig {
        @Bean
        fun expoClient(): ExpoClient = Mockito.mock(ExpoClient::class.java)

        @Bean
        fun userClient(): UserClient =
            Mockito.mock(UserClient::class.java).also { client ->
                Mockito.`when`(client.trainee(Mockito.anyLong())).thenAnswer { invocation ->
                    TraineeDetail(invocation.getArgument(0), "expo-1", null, null, InformationResDto(StubProvider.JSON.readTree("{}")))
                }
            }

        @Bean
        @Primary
        fun fakeReportSource() = FakeReportSource()
    }

    class FakeReportSource : ReportSource {
        var standard: List<StandardParticipant>? = null
        var trainees: List<Trainee>? = null
        var program: List<ProgramParticipant>? = null
        var attendance: TraineeAttendance? = null
        var failure: Exception? = null
        val calls = mutableListOf<String>()

        fun reset() {
            standard = null
            trainees = null
            program = null
            attendance = null
            failure = null
            calls.clear()
        }

        private fun <T> answer(
            call: String,
            value: T,
        ): T {
            calls += call
            failure?.let { throw it }
            return value
        }

        override fun standardParticipants(expoId: String) = answer("standard:$expoId", standard)

        override fun trainees(expoId: String) = answer("trainees:$expoId", trainees)

        override fun programParticipants(
            expoId: String,
            programId: Long,
        ) = answer("program:$expoId:$programId", program)

        override fun traineeAttendance(traineeId: Long) = answer("attendance:$traineeId", attendance)
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun jwt(registry: DynamicPropertyRegistry) {
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
        }
    }
}
