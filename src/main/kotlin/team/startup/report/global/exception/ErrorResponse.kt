package team.startup.report.global.exception

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "API 오류 응답")
data class ErrorResponse(
    @field:Schema(description = "HTTP 상태 코드", example = "400")
    val status: Int,
    @field:Schema(description = "오류 설명", example = "잘못된 요청입니다.")
    val message: String,
)
