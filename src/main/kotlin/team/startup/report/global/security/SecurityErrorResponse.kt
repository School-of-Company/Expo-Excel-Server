package team.startup.report.global.security

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import team.startup.report.global.exception.ErrorResponse
import tools.jackson.databind.json.JsonMapper

internal fun HttpServletResponse.writeError(
    jsonMapper: JsonMapper,
    status: Int,
    message: String,
) {
    this.status = status
    characterEncoding = "UTF-8"
    contentType = MediaType.APPLICATION_JSON_VALUE
    jsonMapper.writeValue(writer, ErrorResponse(status, message))
}
