package team.startup.report.domain.excel.presentation

import com.fasterxml.jackson.annotation.JsonAnySetter
import team.startup.report.global.client.ApplicationFilter
import tools.jackson.databind.JsonNode
import java.util.UUID

enum class ExcelTemplate { STANDARD, TRAINEE, PROGRAM, ATTENDANCE }

enum class DownloadScope { SELECTED, FILTER }

data class DownloadRequest(
    val template: ExcelTemplate,
    val scope: DownloadScope,
    val applicationIds: List<UUID>? = null,
    val filter: ApplicationFilter? = null,
    val programId: Long? = null,
    val traineeId: Long? = null,
) {
    @JsonAnySetter
    fun rejectUnknown(
        name: String,
        value: JsonNode,
    ): Nothing = throw IllegalArgumentException("Unsupported field: $name")
}
