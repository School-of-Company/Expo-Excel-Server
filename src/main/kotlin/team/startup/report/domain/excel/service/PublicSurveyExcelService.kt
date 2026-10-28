package team.startup.report.domain.excel.service

interface PublicSurveyExcelService {
    fun execute(expoId: String): ExcelFile
}
