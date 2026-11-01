package team.startup.report.domain.excel.service

import team.startup.report.global.client.SessionType
import java.util.UUID

interface ProgramAttendanceExcelService {
    fun execute(
        expoId: UUID,
        programType: SessionType,
        programId: Long,
    ): ExcelFile
}
