package team.startup.report.domain.excel.presentation

import io.swagger.v3.oas.annotations.Operation
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import team.startup.report.domain.excel.service.impl.ExcelDownloadService
import team.startup.report.global.exception.ExpectedException
import java.io.ByteArrayOutputStream
import java.util.UUID

@RestController
class ExcelDownloadController(
    private val service: ExcelDownloadService,
) {
    @Operation(summary = "기존 XLSX 양식의 선택 신청 또는 필터 전체 다운로드")
    @PostMapping("/excel/expos/{expoId}/downloads")
    fun download(
        @PathVariable expoId: String,
        @RequestBody request: DownloadRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ByteArray> {
        if (servletRequest.parameterMap.isNotEmpty()) throw ExpectedException(HttpStatus.BAD_REQUEST, "쿼리 대신 요청 본문의 범위를 사용해 주세요.")
        val eventId =
            try {
                UUID.fromString(expoId).also {
                    if (!it.toString().equals(expoId, ignoreCase = true)) throw IllegalArgumentException()
                }
            } catch (_: IllegalArgumentException) {
                throw ExpectedException(HttpStatus.BAD_REQUEST, "행사 ID는 UUID여야 합니다.")
            }
        val file = service.execute(eventId, request)
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
