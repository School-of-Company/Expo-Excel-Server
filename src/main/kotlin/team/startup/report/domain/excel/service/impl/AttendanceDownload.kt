package team.startup.report.domain.excel.service.impl

import team.startup.report.domain.excel.presentation.dto.request.DownloadRequest
import team.startup.report.domain.excel.renderer.renderTraineeAttendance
import team.startup.report.domain.excel.service.ExcelFile
import team.startup.report.domain.excel.service.TraineeAttendance
import team.startup.report.domain.excel.service.informationAnswers
import team.startup.report.global.client.application.ApplicationRow

internal fun DownloadTemplates.attendance(
    expoId: String,
    request: DownloadRequest,
    rows: List<ApplicationRow>,
): ExcelFile {
    val person = user.trainee(request.traineeId!!) ?: error("연수자 없음")
    check(
        person.traineeId == request.traineeId && person.expoId == expoId &&
            rows.all { it.participantId == person.participantId },
    )
    val registration =
        person.registration?.let { informationAnswers(it.answers, it.questions).values }.orEmpty() -
            setOf("이름", "연수원아이디", "전화번호") + informationAnswers(person.information.answers, person.information.questions).values
    val info = existingAnswers(registration, rows, setOf("학교명", "소속"))
    val programs = programs(rows)
    val expoSummary = expo.expo(expoId)!!
    val attendance =
        TraineeAttendance(
            expoSummary.title,
            person.name,
            person.trainingId,
            info,
            programs,
            person.school,
            expoSummary.location,
        )
    return ExcelFile(
        "attachment; filename=\"Trainee_Attendance.xlsx\"",
        renderTraineeAttendance(
            attendance,
            programs
                .map {
                    it.startDate
                }.distinct()
                .sorted(),
        ),
    )
}
