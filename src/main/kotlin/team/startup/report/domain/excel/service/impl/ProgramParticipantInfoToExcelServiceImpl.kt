package team.startup.report.domain.excel.service.impl

import org.springframework.stereotype.Service
import team.startup.report.domain.excel.service.ExcelFile
import team.startup.report.domain.excel.service.ProgramParticipantInfoToExcelService
import team.startup.report.domain.excel.service.ReportSource

/** GET /excel/program/{expo_id}?programId= ; v1은 참가자가 0명이어도 머리글만 있는 파일을 준다 */
@Service
class ProgramParticipantInfoToExcelServiceImpl(
    private val source: ReportSource,
) : ProgramParticipantInfoToExcelService {
    override fun execute(
        expoId: String,
        programId: Long,
    ): ExcelFile =
        wrapExcelFailure({ "엑셀 파일 생성 중 오류 발생" }) {
            val participants = source.programParticipants(expoId, programId) ?: throw excelNotFound("프로그램을 찾을 수 없습니다.")
            ExcelFile("attachment; filename=\"Program_Participant_Information.xlsx\"", renderProgramParticipants(participants))
        }
}
