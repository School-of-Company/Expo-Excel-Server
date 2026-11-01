package team.startup.report.domain.excel.presentation.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import team.startup.report.domain.excel.presentation.dto.request.DownloadRequest
import team.startup.report.domain.excel.service.ExcelDownloadService
import team.startup.report.global.exception.ErrorResponse
import team.startup.report.global.exception.ExpectedException
import java.util.UUID

@RestController
@SecurityRequirement(name = "bearerAuth")
class ExcelDownloadController(
    private val service: ExcelDownloadService,
) {
    @Operation(
        summary = "기존 XLSX 양식의 선택 신청 또는 필터 전체 다운로드",
        description = "선택 최대 1000개, 필터 전체 최대 10000개. PROGRAM은 단일 회차, ATTENDANCE는 본인 확정 연수 신청만 허용합니다. 기본 상태는 CONFIRMED입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "XLSX 파일(no-store)",
                content = [
                    Content(
                        mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        schema = Schema(type = "string", format = "binary"),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청·범위·빈 대상·상태·회차·답변 충돌",
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
                description = "행사 또는 선택 신청 없음·타 행사 선택",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "410",
                description = "신청 대상 스냅샷 만료",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "413",
                description = "전체 신청 상한 초과",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "500",
                description = "예상하지 못한 파일 쓰기 오류",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "502",
                description = "원천 장애·응답 대상/형식/스냅샷 불일치",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "504",
                description = "요청 데드라인(58초) 초과. Gateway idle 60초에 앞서 남은 원천 조회를 멈춤",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "503",
                description = "엑셀 동시 다운로드 한도 초과",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    @PostMapping("/excel/expos/{expoId}/downloads")
    fun download(
        @Parameter(description = "박람회 UUID", example = "11111111-1111-1111-1111-111111111111") @PathVariable expoId: String,
        @RequestBody request: DownloadRequest,
        servletRequest: HttpServletRequest,
        res: HttpServletResponse,
    ) {
        if (servletRequest.parameterMap.isNotEmpty()) throw ExpectedException(HttpStatus.BAD_REQUEST, "쿼리 대신 요청 본문의 범위를 사용해 주세요.")
        val eventId =
            try {
                UUID.fromString(expoId).also {
                    if (!it.toString().equals(expoId, ignoreCase = true)) throw IllegalArgumentException()
                }
            } catch (_: IllegalArgumentException) {
                throw ExpectedException(HttpStatus.BAD_REQUEST, "행사 ID는 UUID여야 합니다.")
            }
        service.execute(eventId, request).respond(res)
    }
}
