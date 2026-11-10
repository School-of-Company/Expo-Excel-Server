package team.startup.report.domain.excel.service.impl

import team.startup.report.domain.excel.presentation.dto.request.DownloadRequest
import team.startup.report.domain.excel.renderer.renderProgramParticipants
import team.startup.report.domain.excel.service.ExcelFile
import team.startup.report.domain.excel.service.PARTICIPANT_TYPE_LABELS
import team.startup.report.domain.excel.service.ProgramApplicationColumns
import team.startup.report.domain.excel.service.ProgramParticipant
import team.startup.report.domain.excel.service.idKeyedAnswers
import team.startup.report.global.client.application.ApplicationRow
import team.startup.report.global.common.time.SEOUL
import java.time.format.DateTimeFormatter

// 신청 경로(Application admission_route)와 신청 시각(KST) 표기
private val ADMISSION_ROUTE_LABELS = mapOf("PRE_REGISTRATION" to "사전", "ONSITE" to "현장")
private val APPLIED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(SEOUL)

internal fun DownloadTemplates.program(
    expoId: String,
    request: DownloadRequest,
    rows: List<ApplicationRow>,
): ExcelFile {
    val ids = rows.map { it.participantId }.toSet()
    val byParticipant = rows.groupBy { it.participantId }
    check(expo.standardProgram(expoId, request.programId!!) != null)
    // 소속(학교)은 상세 목록에만 있다. ponytail: 행사 전체를 읽는다. 느려지면 User 일괄 조회에 school을 더한다
    val people = user.allStandardParticipants(expoId).filter { it.participantId in ids }
    people.checkIds(ids) { it.participantId }
    return ExcelFile(
        "attachment; filename=\"Program_Participant_Information.xlsx\"",
        renderProgramParticipants(
            people.sortedBy { it.participantId }.map { person ->
                // 같은 사람의 확정 신청이 둘 이상이면 먼저 접수된 신청을 쓴다
                val row = byParticipant.getValue(person.participantId).minBy { it.createdAt }
                ProgramParticipant(
                    person.name,
                    person.phoneNumber,
                    person.personalInformationStatus,
                    ProgramApplicationColumns(
                        affiliation = person.school.requiredText(),
                        occupation = person.occupation?.let { PARTICIPANT_TYPE_LABELS[it] ?: it },
                        admissionRoute = row.admissionRoute?.let { ADMISSION_ROUTE_LABELS[it] ?: it },
                        appliedAt = APPLIED_AT.format(row.createdAt),
                        answers = idKeyedAnswers(row.answers, row.questions).values,
                    ),
                )
            },
        ),
    )
}
