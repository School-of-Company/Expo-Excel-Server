package team.startup.report.domain.excel.renderer

import team.startup.report.global.client.ApplicationRow
import team.startup.report.global.client.ApplicationStatus
import team.startup.report.global.client.ProgramAttendanceRecord
import team.startup.report.global.client.SessionType
import team.startup.report.global.common.time.PROGRAM_SCHEDULE
import team.startup.report.global.common.time.parseProgramDateTime

data class AttendanceProgram(
    val title: String?,
    val startedAt: String?,
    val endedAt: String?,
)

data class AttendancePerson(
    val name: String?,
    val affiliation: String?,
)

/** 출력 한 행. [personId]는 일반 참가자 ID 또는 연수자 ID이고, 연수자와 연결되지 않은 연수 신청이면 null이다. */
data class AttendanceRow(
    val personId: Long?,
    val person: AttendancePerson?,
    val application: ApplicationRow?,
    val record: ProgramAttendanceRecord?,
)

private val STATUS_LABELS =
    mapOf(ApplicationStatus.CONFIRMED to "확정", ApplicationStatus.WAITING to "대기", ApplicationStatus.CANCELLED to "취소")

/** 시트 "실제 출석 결과"(확정 신청자)와 "신청 불일치 출석"(확정 신청 없는 출석). 대상이 없어도 머리글만 있는 두 시트를 만든다. */
fun renderProgramAttendance(
    programType: SessionType,
    programId: Long,
    program: AttendanceProgram,
    confirmed: List<AttendanceRow>,
    mismatched: List<AttendanceRow>,
) = newWorkbook(500).apply {
    val headerStyle = darkHeaderStyle(this)
    val bodyStyle = createCellStyle().apply { thinBorders() }
    val idHeader = if (programType == SessionType.STANDARD) "참가자 ID" else "연수자 ID"
    val headers =
        listOf("회차 유형", "회차 ID", "회차 제목", "예정 시작", "예정 종료", idHeader, "이름", "소속", "신청 ID", "신청 상태", "실제 출석", "출석일", "입실 시각", "비고")
    val programCells =
        listOf(
            if (programType == SessionType.STANDARD) "일반" else "연수",
            programId.toString(),
            program.title,
            program.startedAt?.let { parseProgramDateTime(it).format(PROGRAM_SCHEDULE) },
            program.endedAt?.let { parseProgramDateTime(it).format(PROGRAM_SCHEDULE) },
        )
    listOf("실제 출석 결과" to confirmed, "신청 불일치 출석" to mismatched).forEach { (name, rows) ->
        val sheet = createSheet(name).apply { defaultColumnWidth = 18 }
        sheet.writeRow(0, headers, headerStyle)
        rows.forEachIndexed { index, row ->
            val note =
                when {
                    row.personId == null -> "연수자 미연결 신청: 출석 확인 불가"
                    row.person == null -> "참가자 정보 없음"
                    else -> null
                }
            val attendance =
                when {
                    row.personId == null -> null
                    row.record != null -> "출석"
                    else -> "미출석"
                }
            sheet.writeRow(
                index + 1,
                (
                    programCells +
                        listOf(
                            row.personId?.toString(),
                            row.person?.name,
                            row.person?.affiliation,
                            row.application?.applicationId?.toString(),
                            row.application?.status?.let(STATUS_LABELS::getValue) ?: "신청 없음",
                            attendance,
                            row.record?.attendanceDate?.toString(),
                            row.record?.entryTime,
                            note,
                        )
                ).map { it?.let(::safe) },
                bodyStyle,
            )
        }
    }
}
