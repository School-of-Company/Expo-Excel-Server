package team.startup.report.domain.excel.presentation.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import team.startup.report.domain.excel.service.PublicSurveyExcelService
import team.startup.report.global.exception.ErrorResponse
import java.io.ByteArrayOutputStream

@RestController
@SecurityRequirement(name = "bearerAuth")
class PublicSurveyExcelController(
    private val service: PublicSurveyExcelService,
) {
    @Operation(
        summary = "공개 만족도 조사 응답 엑셀 다운로드",
        description =
            "행사 공통 공개 설문(직업과 무관한 1개)의 익명 응답을 제출 순으로 응답 1건 = 1행 출력합니다. " +
                "참가자 명단과 연결하지 않으며 전화번호·경품 정보는 담지 않습니다. 최대 100,000건입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "XLSX 파일(no-store). 응답이 없어도 머리글만 있는 파일입니다.",
                content = [
                    Content(
                        mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        schema = Schema(type = "string", format = "binary"),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "401",
                description = "JWT 누락·만료·검증 실패",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "403",
                description = "관리자 역할 또는 행사 권한 없음",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "행사 또는 행사의 공개 설문 없음",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "413",
                description = "응답이 100,000건 초과",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "502",
                description = "설문 서비스 장애·응답 형식/건수 불일치(부분 결과로 파일을 만들지 않음)",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "504",
                description = "요청 데드라인(58초) 초과. Gateway idle 60초에 앞서 남은 원천 조회를 멈춤",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "503",
                description = "설문 서비스 연결 미설정",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    @GetMapping("/excel/public-surveys/{expo_id}")
    fun publicSurveyAnswersToExcel(
        @Parameter(description = "박람회 ID", example = "11111111-1111-1111-1111-111111111111") @PathVariable("expo_id") expoId: String,
    ): ResponseEntity<ByteArray> {
        val file = service.execute(expoId)
        val bytes =
            file.workbook.use { workbook ->
                try {
                    ByteArrayOutputStream().use { output ->
                        workbook.write(output)
                        output.toByteArray()
                    }
                } finally {
                    workbook.dispose()
                }
            }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, file.contentDisposition)
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(bytes)
    }
}
