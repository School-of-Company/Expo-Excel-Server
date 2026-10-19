package team.startup.report.domain.excel.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import team.startup.report.domain.excel.service.impl.EntryRecordExcelService
import team.startup.report.global.exception.ErrorResponse
import team.startup.report.global.exception.ExpectedException
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeParseException

@RestController
@SecurityRequirement(name = "bearerAuth")
class EntryRecordExcelController(
    private val service: EntryRecordExcelService,
) {
    @Operation(
        summary = "입장 기록 엑셀 다운로드",
        description =
            "참여 서비스의 입장·재입장·퇴장 기록을 발생 시각 순으로 출력하고, 간편입장 집계는 별도 시트에 담습니다. " +
                "from·to(yyyy-MM-dd, 둘 다 포함)는 함께 주거나 모두 생략(박람회 전체 기간)합니다. 최대 1,000,000건입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "XLSX 파일(no-store). 기록이 없어도 머리글만 있는 파일입니다.",
                content = [
                    Content(
                        mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        schema = Schema(type = "string", format = "binary"),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "날짜 형식 오류·from만 또는 to만 지정·from이 to보다 늦음",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "JWT 누락·만료·검증 실패",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "403",
                description = "관리자 역할 없음",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "행사 없음",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "413",
                description = "기록이 1,000,000건 초과",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "502",
                description = "참여·유저 서비스 장애 또는 응답 오류(부분 결과로 파일을 만들지 않음)",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "503",
                description = "참여 서비스 연결 미설정",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    @GetMapping("/excel/entry-records/{expo_id}")
    fun entryRecordsToExcel(
        @Parameter(description = "박람회 ID", example = "11111111-1111-1111-1111-111111111111") @PathVariable("expo_id") expoId: String,
        @Parameter(description = "입장 날짜 시작(포함, yyyy-MM-dd). to와 함께 지정", example = "2026-10-19")
        @RequestParam("from", required = false) from: String?,
        @Parameter(description = "입장 날짜 끝(포함, yyyy-MM-dd). from과 함께 지정", example = "2026-10-20")
        @RequestParam("to", required = false) to: String?,
    ): ResponseEntity<ByteArray> {
        val file = service.execute(expoId, from?.toDate("from"), to?.toDate("to"))
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

    private fun String.toDate(name: String): LocalDate =
        try {
            LocalDate.parse(this)
        } catch (_: DateTimeParseException) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "$name 은 yyyy-MM-dd 형식이어야 합니다.")
        }
}
