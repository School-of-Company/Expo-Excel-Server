package team.startup.report.domain.excel.service.impl

import org.springframework.stereotype.Service
import org.springframework.web.util.UriUtils
import team.startup.report.domain.excel.service.ExcelFile
import team.startup.report.domain.excel.service.ReportSource
import team.startup.report.domain.excel.service.TraineeInfoToExcelService
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** GET /excel/{expo_id} */
@Service
class TraineeInfoToExcelServiceImpl(
    private val source: ReportSource,
) : TraineeInfoToExcelService {
    override fun execute(expoId: String): ExcelFile =
        wrapExcelFailure({ "예상치 못한 오류 발생" }) {
            val trainees = source.trainees(expoId) ?: throw excelNotFound("박람회를 찾을 수 없습니다.")
            if (trainees.isEmpty()) throw excelNotFound("연수자가 존재하지 않습니다.")
            val timestamp = LocalDateTime.now(SEOUL).format(TIMESTAMP)
            val filename = UriUtils.encode("교원연수자_정보_$timestamp.xlsx", Charsets.UTF_8)
            ExcelFile("attachment; filename*=UTF-8''$filename", renderTrainees(trainees))
        }

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}
