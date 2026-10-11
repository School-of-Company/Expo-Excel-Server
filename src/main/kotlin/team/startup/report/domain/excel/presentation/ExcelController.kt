package team.startup.report.domain.excel.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import team.startup.report.domain.excel.service.ExcelFile
import team.startup.report.domain.excel.service.ProgramParticipantInfoToExcelService
import team.startup.report.domain.excel.service.StandardParticipantInfoToExcelService
import team.startup.report.domain.excel.service.TraineeAttendanceToExcelService
import team.startup.report.domain.excel.service.TraineeInfoToExcelService
import team.startup.report.global.exception.ErrorResponse

/** v1 ExcelController와 같은 경로·파라미터·응답(200, 본문은 XLSX 바이트). */
@RestController
@RequestMapping("/excel")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses(
    value = [
        ApiResponse(
            responseCode = "200",
            description = "XLSX 파일",
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
            description = "관리자 역할 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "500",
            description = "v1 호환: 대상 없음·숫자 변환 실패·원천 오류·파일 생성 실패",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    ],
)
class ExcelController(
    private val traineeInfoToExcelService: TraineeInfoToExcelService,
    private val standardParticipantInfoToExcelService: StandardParticipantInfoToExcelService,
    private val programParticipantInfoToExcelService: ProgramParticipantInfoToExcelService,
    private val traineeAttendanceToExcelService: TraineeAttendanceToExcelService,
) {
    @GetMapping("/{expo_id}")
    @Operation(summary = "교원 연수자 명단 다운로드", description = "행사의 전체 연수자와 신청 강연을 기존 양식으로 출력합니다. 대상 없음과 빈 명단은 v1 호환 500입니다.")
    fun traineeInfoToExcel(
        @Parameter(description = "박람회 ID", example = "11111111-1111-1111-1111-111111111111") @PathVariable("expo_id") expoId: String,
        res: HttpServletResponse,
    ) = traineeInfoToExcelService.execute(expoId).writeTo(res)

    @GetMapping("/standard/{expo_id}")
    @Operation(summary = "박람회 참가자 명단 다운로드", description = "등록 답변과 설문을 출력합니다. 행사 없음·빈 명단·공급자 실패는 v1 호환 500입니다.")
    fun standardParticipantInfoToExcel(
        @Parameter(description = "박람회 ID", example = "11111111-1111-1111-1111-111111111111") @PathVariable("expo_id") expoId: String,
        res: HttpServletResponse,
    ) = standardParticipantInfoToExcelService.execute(expoId).writeTo(res)

    @GetMapping("/program/{expo_id}")
    @ApiResponse(
        responseCode = "400",
        description = "필수 programId 누락",
        content = [Content(schema = Schema(implementation = ErrorResponse::class))],
    )
    @Operation(summary = "일반 프로그램 참가자 명단 다운로드", description = "일반 프로그램 한 회차의 명단입니다. 참가자 0명은 머리글만 출력하며 프로그램 없음은 v1 호환 500입니다.")
    fun programParticipantInfoToExcel(
        @Parameter(description = "박람회 ID", example = "11111111-1111-1111-1111-111111111111") @PathVariable("expo_id") expoId: String,
        @Parameter(description = "일반 프로그램 회차 ID", example = "7") @RequestParam("programId") programId: Long,
        res: HttpServletResponse,
    ) = programParticipantInfoToExcelService.execute(expoId, programId).writeTo(res)

    @GetMapping("/trainee/{trainee_id}")
    @Operation(summary = "개인 연수 출석부 다운로드", description = "신청 강연의 일정으로 만드는 작성용 양식이며 실제 출석/이수 결과가 아닙니다. 연수자·신청 프로그램 없음은 v1 호환 500입니다.")
    fun traineeAttendanceToExcel(
        @Parameter(description = "연수자 ID", example = "10") @PathVariable("trainee_id") traineeId: Long,
        res: HttpServletResponse,
    ) = traineeAttendanceToExcelService.execute(traineeId).writeTo(res)

    private fun ExcelFile.writeTo(res: HttpServletResponse) {
        res.contentType = XLSX_CONTENT_TYPE
        res.setHeader(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
        workbook.use { workbook ->
            try {
                res.outputStream.use { workbook.write(it) }
            } finally {
                workbook.dispose()
            }
        }
    }

    private companion object {
        const val XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}
