package team.startup.report.domain.excel.service.impl

import org.springframework.stereotype.Service
import team.startup.report.domain.excel.renderer.renderStandardParticipants
import team.startup.report.domain.excel.service.ExcelFile
import team.startup.report.domain.excel.service.ReportSource
import team.startup.report.domain.excel.service.StandardParticipantInfoToExcelService

/** GET /excel/standard/{expo_id} */
@Service
class StandardParticipantInfoToExcelServiceImpl(
    private val source: ReportSource,
) : StandardParticipantInfoToExcelService {
    override fun execute(expoId: String): ExcelFile =
        wrapExcelFailure({ "엑셀 파일 생성 중 오류 발생: ${it.message}" }) {
            val participants = source.standardParticipants(expoId) ?: throw excelNotFound("박람회를 찾을 수 없습니다.")
            if (participants.isEmpty()) throw excelNotFound("참가자 정보가 존재하지 않습니다.")
            ExcelFile("attachment; filename*=UTF-8''Participant_Information.xlsx", renderStandardParticipants(participants))
        }
}
