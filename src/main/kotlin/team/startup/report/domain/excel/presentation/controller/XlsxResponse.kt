package team.startup.report.domain.excel.presentation.controller

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import team.startup.report.domain.excel.service.ExcelFile
import java.nio.file.Files

private const val XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/**
 * 문서를 temp 파일에 다 쓴 뒤에만 헤더를 쓰고 Content-Length와 함께 보낸다(힙에 파일 전체를 두지 않음).
 * temp 파일은 쓰기·전송 실패에도 지운다.
 */
internal fun ExcelFile.respond(res: HttpServletResponse) {
    val tmp =
        workbook.use { workbook ->
            try {
                Files.createTempFile("report-excel-", ".xlsx").also { tmp ->
                    try {
                        Files.newOutputStream(tmp).use { workbook.write(it) }
                    } catch (e: Throwable) {
                        Files.deleteIfExists(tmp)
                        throw e
                    }
                }
            } finally {
                workbook.dispose()
            }
        }
    try {
        res.contentType = XLSX_CONTENT_TYPE
        res.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().headerValue)
        res.setHeader(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
        res.setContentLengthLong(Files.size(tmp))
        Files.copy(tmp, res.outputStream)
    } finally {
        Files.deleteIfExists(tmp)
    }
}
