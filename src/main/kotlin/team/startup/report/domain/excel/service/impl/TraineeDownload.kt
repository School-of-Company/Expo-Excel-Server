package team.startup.report.domain.excel.service.impl

import team.startup.report.domain.excel.renderer.renderTrainees
import team.startup.report.domain.excel.service.ExcelFile
import team.startup.report.domain.excel.service.Trainee
import team.startup.report.global.client.application.ApplicationRow
import team.startup.report.global.client.user.readAll

internal fun DownloadTemplates.trainee(
    expoId: String,
    rows: List<ApplicationRow>,
): ExcelFile {
    val byTrainee = rows.groupBy { it.traineeId }
    val traineeIds = rows.map { it.traineeId!! }.toSet()
    val people = readAll { user.traineePage(expoId, it) }.filter { it.traineeId in traineeIds }
    check(people.map { it.traineeId }.toSet() == traineeIds && people.size == traineeIds.size)
    val participants =
        if (people.any {
                it.participantId != null
            }
        ) {
            readAll { user.standardParticipantPage(expoId, it) }.associateBy { it.participantId }
        } else {
            emptyMap()
        }
    val result =
        people.sortedBy { it.traineeId }.map { person ->
            val selected = byTrainee.getValue(person.traineeId)
            check(selected.all { it.participantId == person.participantId })
            val registration = person.rosterInformation(participants).values
            Trainee(
                person.name,
                person.trainingId,
                person.phoneNumber,
                person.applicationType,
                existingAnswers(registration, selected),
                programs(selected),
                person.school,
                person.participantId?.let { participants.getValue(it).notificationPhoneNumber },
            )
        }
    return ExcelFile("attachment; filename=\"Trainee_Information.xlsx\"", renderTrainees(result))
}
