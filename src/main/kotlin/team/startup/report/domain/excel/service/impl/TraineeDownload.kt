package team.startup.report.domain.excel.service.impl

import team.startup.report.domain.excel.renderer.renderTrainees
import team.startup.report.domain.excel.service.ExcelFile
import team.startup.report.domain.excel.service.Trainee
import team.startup.report.global.client.application.ApplicationRow

internal fun DownloadTemplates.trainee(
    expoId: String,
    rows: List<ApplicationRow>,
): ExcelFile {
    val byTrainee = rows.groupBy { it.traineeId }
    val traineeIds = rows.map { it.traineeId!! }.toSet()
    val people = user.allTrainees(expoId).filter { it.traineeId in traineeIds }
    people.checkIds(traineeIds) { it.traineeId }
    val linked = people.any { it.participantId != null }
    val participants = if (linked) user.allStandardParticipants(expoId).associateBy { it.participantId } else emptyMap()
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
