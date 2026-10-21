package team.startup.report.domain.excel.presentation

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.swagger.v3.oas.annotations.media.Schema
import team.startup.report.global.client.ApplicationFilter
import tools.jackson.databind.JsonNode
import java.util.UUID

// APPLICATION_LEDGER는 프로그램·연수 신청 1건 = 1행 원장이다(Report #80). 나머지는 기존 사람 명단·인쇄 양식이다
enum class ExcelTemplate { STANDARD, TRAINEE, PROGRAM, ATTENDANCE, APPLICATION_LEDGER }

enum class DownloadScope { SELECTED, FILTER }

@Schema(description = "기존 XLSX 양식과 신청 대상 범위. 화면 page/size와 알 수 없는 필드는 허용하지 않습니다.")
data class DownloadRequest(
    @field:Schema(description = "출력 양식", example = "PROGRAM", requiredMode = Schema.RequiredMode.REQUIRED)
    val template: ExcelTemplate,
    @field:Schema(description = "SELECTED는 선택 신청, FILTER는 필터 전체", example = "SELECTED", requiredMode = Schema.RequiredMode.REQUIRED)
    val scope: DownloadScope,
    @field:Schema(description = "선택 범위의 UUID 신청 ID(중복 없이 1–1000개). 필터와 동시 지정 불가", example = "[\"22222222-2222-2222-2222-222222222222\"]")
    val applicationIds: List<UUID>? = null,
    @field:Schema(description = "FILTER 범위 전용 관리자 조회 필터")
    val filter: ApplicationFilter? = null,
    @field:Schema(description = "PROGRAM 양식 전용 필수 단일 회차 ID", example = "7")
    val programId: Long? = null,
    @field:Schema(description = "ATTENDANCE 양식 전용 필수 본인 연수자 ID", example = "10")
    val traineeId: Long? = null,
) {
    @JsonAnySetter
    fun rejectUnknown(
        name: String,
        value: JsonNode,
    ): Nothing = throw IllegalArgumentException("Unsupported field: $name")
}
