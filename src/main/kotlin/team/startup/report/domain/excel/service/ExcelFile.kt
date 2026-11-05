package team.startup.report.domain.excel.service

import org.apache.poi.xssf.streaming.SXSSFWorkbook

/** 다 만든 통합 문서와 v1 Content-Disposition 값. 헤더는 문서를 다 만든 뒤에만 쓴다(v1과 같은 순서). */
class ExcelFile(
    val contentDisposition: String,
    val workbook: SXSSFWorkbook,
) {
    companion object {
        fun attachment(
            filename: String,
            workbook: SXSSFWorkbook,
        ): ExcelFile = ExcelFile("attachment; filename*=UTF-8''$filename", workbook)
    }
}
