package team.startup.report.domain.excel.service

import java.time.LocalDate

interface EntryRecordExcelService {
    fun execute(
        expoId: String,
        from: LocalDate?,
        to: LocalDate?,
    ): ExcelFile
}
