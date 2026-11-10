package team.startup.report.domain.excel.service.impl

import org.springframework.web.util.UriUtils
import team.startup.report.domain.excel.presentation.dto.request.DownloadRequest
import team.startup.report.domain.excel.renderer.LedgerMeta
import team.startup.report.domain.excel.renderer.renderApplicationLedger
import team.startup.report.domain.excel.service.ExcelFile
import team.startup.report.global.client.application.ApplicationRow
import team.startup.report.global.common.time.SEOUL
import team.startup.report.global.common.time.TIMESTAMP
import java.time.Instant

private val LEDGER_TIMESTAMP = TIMESTAMP.withZone(SEOUL)

internal fun DownloadTemplates.applicationLedger(
    expoId: String,
    request: DownloadRequest,
    rows: List<ApplicationRow>,
): ExcelFile {
    val ids = rows.map { it.participantId }.toSet()
    val people = user.allStandardParticipants(expoId).filter { it.participantId in ids }
    people.checkIds(ids) { it.participantId }
    val traineeIds = rows.mapNotNullTo(HashSet()) { it.traineeId }
    val trainees =
        if (traineeIds.isEmpty()) emptyList() else user.allTrainees(expoId).filter { it.traineeId in traineeIds }
    trainees.checkIds(traineeIds) { it.traineeId }
    // 이름·연락처·이력·접수번호가 빠진 원천은 정상 미응답처럼 보이므로 부분 파일을 만들지 않는다
    check(rows.all { it.receiptNumber != null && it.history != null })
    val byTrainee = rows.groupBy { it.traineeId }
    check(
        trainees.all { trainee ->
            byTrainee[trainee.traineeId].orEmpty().all { it.participantId == trainee.participantId }
        },
    )
    val timestamp = Instant.now()
    val filename = UriUtils.encode("신청원장_${LEDGER_TIMESTAMP.format(timestamp)}.xlsx", Charsets.UTF_8)
    return ExcelFile.attachment(
        filename,
        renderApplicationLedger(
            rows,
            people.associateBy { it.participantId },
            trainees.associateBy { it.traineeId },
            LedgerMeta(timestamp, expoId, request.scope.name, request.filter, request.applicationIds?.size),
        ),
    )
}
