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
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import team.startup.report.domain.excel.service.ProgramAttendanceExcelService
import team.startup.report.global.client.SessionType
import team.startup.report.global.exception.ErrorResponse
import team.startup.report.global.exception.ExpectedException
import java.io.ByteArrayOutputStream
import java.util.UUID

@RestController
@SecurityRequirement(name = "bearerAuth")
class ProgramAttendanceExcelController(
    private val service: ProgramAttendanceExcelService,
) {
    @Operation(
        summary = "회차 실제 출석 결과 엑셀 다운로드",
        description =
            "한 회차의 확정 신청자와 출석 서비스의 실제 출석일·입실 시각을 결합합니다(일반은 참가자 ID, 연수는 연수자 ID). " +
                "확정 신청 없이 출석한 사람은 별도 시트에 남깁니다. 개인 수기 출석부와 다르며 퇴실·이수 결과는 없습니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "XLSX 파일(no-store). 대상이 없어도 머리글만 있는 파일입니다.",
                content = [
                    Content(
                        mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        schema = Schema(type = "string", format = "binary"),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "행사 ID가 UUID가 아님·회차 유형/ID 누락 또는 잘못됨",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
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
                description = "행사 없음·회차 없음 또는 다른 행사 회차",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "410",
                description = "신청 대상 스냅샷 만료",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "413",
                description = "회차 신청 10,000건 초과",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "502",
                description = "신청·유저·행사·출석 서비스 장애 또는 응답 계약 위반(부분 결과·전원 미출석으로 파일을 만들지 않음)",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "504",
                description = "요청 데드라인(58초) 초과. Gateway idle 60초에 앞서 남은 원천 조회를 멈춤",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "503",
                description = "출석 서비스 연결 미설정",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    @GetMapping("/excel/expos/{expoId}/program-attendances")
    fun download(
        @Parameter(description = "박람회 UUID", example = "11111111-1111-1111-1111-111111111111") @PathVariable expoId: String,
        @Parameter(description = "회차 유형", example = "STANDARD") @RequestParam("programType") programType: SessionType,
        @Parameter(description = "회차 ID", example = "7") @RequestParam("programId") programId: Long,
    ): ResponseEntity<ByteArray> {
        val eventId =
            try {
                UUID.fromString(expoId).also {
                    if (!it.toString().equals(expoId, ignoreCase = true)) throw IllegalArgumentException()
                }
            } catch (_: IllegalArgumentException) {
                throw ExpectedException(HttpStatus.BAD_REQUEST, "행사 ID는 UUID여야 합니다.")
            }
        val file = service.execute(eventId, programType, programId)
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
