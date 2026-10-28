package team.startup.report.domain.excel.presentation.controller

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.HttpServletResponse
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.mockito.Mockito
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletResponse
import team.startup.report.domain.excel.service.ExcelFile
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

// 실패 경로에서 temp 파일이 남지 않는지 temp 디렉터리의 report-excel-* 목록으로 확인한다.
class XlsxResponseTests :
    StringSpec({
        "쓰기 실패 - 헤더 없이 temp 파일을 지우고 workbook을 dispose한다" {
            val workbook = TrackingWorkbook(failWrite = true)
            val res = MockHttpServletResponse()
            val before = tempFiles()

            shouldThrow<IOException> { ExcelFile("attachment", workbook).respond(res) }

            tempFiles() shouldBe before
            workbook.disposed shouldBe true
            res.getHeader(HttpHeaders.CONTENT_DISPOSITION) shouldBe null
        }

        "전송 실패 - temp 파일을 지운다" {
            val res = Mockito.mock(HttpServletResponse::class.java)
            Mockito.`when`(res.outputStream).thenThrow(IOException("연결 끊김"))
            val before = tempFiles()

            shouldThrow<IOException> { ExcelFile("attachment", TrackingWorkbook()).respond(res) }

            tempFiles() shouldBe before
        }

        "성공 - Content-Length와 함께 보내고 temp 파일을 지운다" {
            val workbook = TrackingWorkbook().apply { createSheet("A") }
            val res = MockHttpServletResponse()
            val before = tempFiles()

            ExcelFile("attachment", workbook).respond(res)

            tempFiles() shouldBe before
            workbook.disposed shouldBe true
            res.contentLengthLong shouldBe res.contentAsByteArray.size.toLong()
            res.getHeader(HttpHeaders.CACHE_CONTROL) shouldBe "no-store"
        }
    })

private fun tempFiles(): Set<Path> =
    Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { files ->
        files.filter { it.name.startsWith("report-excel-") }.toList().toSet()
    }

private class TrackingWorkbook(
    private val failWrite: Boolean = false,
) : SXSSFWorkbook() {
    var disposed = false

    override fun write(stream: OutputStream) {
        if (failWrite) throw IOException("디스크 가득 참")
        super.write(stream)
    }

    override fun dispose(): Boolean {
        disposed = true
        return super.dispose()
    }
}
