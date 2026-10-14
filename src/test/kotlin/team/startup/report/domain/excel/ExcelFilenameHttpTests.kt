package team.startup.report.domain.excel

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import team.startup.report.domain.excel.service.ApplicationType
import team.startup.report.domain.excel.service.Trainee
import team.startup.report.domain.excel.service.TraineeAttendance
import team.startup.report.domain.excel.service.TrainingCategory
import team.startup.report.domain.excel.service.TrainingProgram
import team.startup.report.global.client.StubProvider
import team.startup.report.support.TestJwt
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ExcelFilenameHttpTests.SourceConfig::class)
class ExcelFilenameHttpTests {
    @Value("\${local.server.port}")
    private var port: Int = 0

    @Autowired
    private lateinit var source: ExcelHttpContractTests.FakeReportSource

    @Test
    fun `실제 Tomcat 응답은 한글 파일명을 보존하고 특수문자와 개행을 헤더 값으로 인코딩한다`() {
        provider.on(
            "GET /internal/expo/expo-1/admin-access",
            body =
                mapOf(
                    "expoId" to "expo-1",
                    "adminId" to 1,
                    "permission" to "APPLICATION_READ",
                    "policyVersion" to "GLOBAL_ADMIN_APPLICATION_READ_V1",
                    "scope" to "GLOBAL_ADMIN",
                ),
        )
        provider.on(
            "GET /internal/trainees/3/details",
            body = mapOf("traineeId" to 3, "expoId" to "expo-1", "information" to mapOf("answers" to emptyMap<String, Any>())),
        )
        source.trainees = listOf(Trainee("교원", "T", "010", ApplicationType.PRE, emptyMap(), emptyList()))
        val trainees = download("/excel/expo-1", "사전 교원연수자 정보")
        ContentDisposition.parse(trainees).filename!! shouldMatch Regex("교원연수자_정보_\\d{8}_\\d{6}\\.xlsx")

        listOf("홍길동", "홍 길동+\";'*/\\%\r\nX-Injected: yes").forEach { name ->
            source.attendance =
                TraineeAttendance(
                    "박람회",
                    name,
                    "T",
                    emptyMap(),
                    listOf(TrainingProgram(1, "강연", TrainingCategory.ESSENTIAL, "2026-05-01 10:00", "2026-05-01 11:00")),
                )
            val header = download("/excel/trainee/3", "출석부")
            ContentDisposition.parse(header).filename shouldBe "Trainee_Attendance_$name.xlsx"
        }
    }

    private fun download(
        path: String,
        sheetName: String,
    ): String {
        val response =
            HttpClient.newHttpClient().send(
                HttpRequest
                    .newBuilder(URI("http://127.0.0.1:$port$path"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${TestJwt.token("ROLE_ADMIN")}")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
        response.statusCode() shouldBe 200
        response.headers().firstValue("X-Injected").isPresent shouldBe false
        response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElseThrow() shouldBe
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        XSSFWorkbook(ByteArrayInputStream(response.body())).use { it.getSheetAt(0).sheetName shouldBe sheetName }
        return response.headers().firstValue(HttpHeaders.CONTENT_DISPOSITION).orElseThrow().also {
            it shouldMatch Regex("attachment; filename\\*=UTF-8''(?:[A-Za-z0-9._~-]|%[0-9A-F]{2})+")
        }
    }

    @TestConfiguration
    class SourceConfig {
        @Bean
        @Primary
        fun filenameReportSource() = ExcelHttpContractTests.FakeReportSource()
    }

    companion object {
        private val provider = StubProvider("filename-test")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
            listOf("user", "application", "expo").forEach {
                registry.add("clients.$it.url") { provider.url() }
                registry.add("clients.$it.internal-token") { "filename-test" }
            }
        }

        @JvmStatic
        @AfterAll
        fun close() = provider.close()
    }
}
