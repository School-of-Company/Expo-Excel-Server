package team.startup.report.domain.excel.service

import team.startup.report.domain.excel.presentation.dto.request.DownloadRequest
import java.util.UUID

interface ExcelDownloadService {
    fun execute(
        expoId: UUID,
        request: DownloadRequest,
    ): ExcelFile
}
