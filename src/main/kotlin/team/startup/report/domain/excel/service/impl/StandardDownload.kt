package team.startup.report.domain.excel.service.impl

import team.startup.report.domain.excel.renderer.renderStandardParticipants
import team.startup.report.domain.excel.service.ExcelFile
import team.startup.report.domain.excel.service.StandardParticipant
import team.startup.report.domain.excel.service.idKeyedAnswers
import team.startup.report.global.client.application.ApplicationRow

internal fun DownloadTemplates.standard(
    expoId: String,
    rows: List<ApplicationRow>,
): ExcelFile {
    val ids = rows.map { it.participantId }.toSet()
    val byParticipant = rows.groupBy { it.participantId }
    // 대표자를 고르지 않아도 동행자의 열 제목을 찾을 수 있게 거르기 전 전체로 찾는다. 출력 대상은 고른 참가자뿐이다.
    val all = user.allStandardParticipants(expoId)
    val byId = all.associateBy { it.participantId }
    val people = all.filter { it.participantId in ids }
    people.checkIds(ids) { it.participantId }
    val result =
        people.sortedBy { it.participantId }.map {
            val registration = it.registration(byId).values
            StandardParticipant(
                it.name,
                it.phoneNumber,
                it.personalInformationStatus,
                it.applicationType,
                existingAnswers(registration, byParticipant.getValue(it.participantId)),
                it.surveyAnswer?.let { survey -> idKeyedAnswers(survey.answers, survey.questions).values } ?: emptyMap(),
            )
        }
    return ExcelFile.attachment("Participant_Information.xlsx", renderStandardParticipants(result))
}
