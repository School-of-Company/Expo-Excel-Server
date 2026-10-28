package team.startup.report.domain.excel.service.impl

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.startup.report.domain.excel.presentation.dto.request.DownloadRequest
import team.startup.report.domain.excel.presentation.dto.request.DownloadScope
import team.startup.report.domain.excel.presentation.dto.request.ExcelTemplate
import team.startup.report.domain.excel.renderer.AttendancePerson
import team.startup.report.domain.excel.renderer.AttendanceProgram
import team.startup.report.domain.excel.renderer.AttendanceRow
import team.startup.report.domain.excel.renderer.renderProgramAttendance
import team.startup.report.domain.excel.service.ExcelFile
import team.startup.report.domain.excel.service.ProgramAttendanceExcelService
import team.startup.report.global.client.ApplicationFilter
import team.startup.report.global.client.ApplicationRow
import team.startup.report.global.client.ApplicationSort
import team.startup.report.global.client.ApplicationStatus
import team.startup.report.global.client.AttendanceClient
import team.startup.report.global.client.ExpoClient
import team.startup.report.global.client.ProgramAttendanceRecord
import team.startup.report.global.client.SessionType
import team.startup.report.global.client.UserClient
import team.startup.report.global.common.time.PROGRAM_SCHEDULE
import team.startup.report.global.common.time.parseProgramDateTime
import team.startup.report.global.exception.ExpectedException
import java.util.UUID

/**
 * GET /excel/expos/{expoId}/program-attendances. 한 회차의 신청(전 상태)과 실제 출석 기록을 사람 ID로 결합한다.
 * 일반은 participantId, 연수는 traineeId로만 연결한다. 출석 조회가 성공했을 때 기록이 없는 확정 신청자만 미출석이다.
 * 확정 신청 없이 출석 기록만 있는 사람은 빼지 않고 "신청 불일치 출석" 시트에 남긴다. 퇴실·이수는 출력하지 않는다.
 * 원천 하나라도 실패하면 파일을 만들지 않는다. 이름·전화번호는 로그에 남기지 않는다.
 */
@Service
class ProgramAttendanceExcelServiceImpl(
    private val downloads: ExcelDownloadServiceImpl,
    private val attendance: AttendanceClient,
    private val user: UserClient,
    private val expo: ExpoClient,
) : ProgramAttendanceExcelService {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun execute(
        expoId: UUID,
        programType: SessionType,
        programId: Long,
    ): ExcelFile {
        if (programId <= 0) throw ExpectedException(HttpStatus.BAD_REQUEST, "회차 ID는 양수여야 합니다.")
        return try {
            val id = expoId.toString()
            if (expo.expo(id) == null) throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾을 수 없습니다.")
            val program =
                when (programType) {
                    SessionType.STANDARD -> {
                        expo
                            .standardProgram(
                                id,
                                programId,
                            )?.let { AttendanceProgram(it.title, it.startedAt, it.endedAt) }
                    }

                    SessionType.TRAINING -> {
                        expo
                            .trainingProgram(
                                id,
                                programId,
                            )?.let { AttendanceProgram(it.title, it.startedAt, it.endedAt) }
                    }
                } ?: throw ExpectedException(HttpStatus.NOT_FOUND, "프로그램을 찾을 수 없습니다.")
            // ponytail: 기존 FILTER 대상 조회(스냅샷·커서·10,000건 상한·행사 검증)를 그대로 쓴다. STANDARD 양식은 필터를 바꾸지 않는다.
            val applications =
                downloads
                    .targets(
                        expoId,
                        DownloadRequest(
                            ExcelTemplate.STANDARD,
                            DownloadScope.FILTER,
                            filter = ApplicationFilter(programType, programId, status = null, sort = ApplicationSort.CREATED_ASC),
                        ),
                    ).map { it.application }
            val records = attendance.programAttendances(programType, programId)
            val people =
                when (programType) {
                    SessionType.STANDARD -> {
                        downloads
                            .readAll { user.standardParticipantPage(id, it) }
                            .associate { it.participantId to AttendancePerson(it.name, it.school.requiredText()) }
                    }

                    SessionType.TRAINING -> {
                        downloads.readAll { user.traineePage(id, it) }.associate { it.traineeId to AttendancePerson(it.name, it.school) }
                    }
                }
            val (confirmed, mismatched) = combine(programType, applications, records, people)
            ExcelFile(
                "attachment; filename=\"Program_Attendance_${programType}_$programId.xlsx\"",
                renderProgramAttendance(programType, programId, program, confirmed, mismatched),
            )
        } catch (e: ExpectedException) {
            throw e
        } catch (e: Exception) {
            // 메시지는 공급자 이름과 상태만 담는다(원인·URL·본문 없음)
            log.error("프로그램 출석 엑셀 생성 실패: {} {}", e.javaClass.simpleName, e.message)
            throw ExpectedException(HttpStatus.BAD_GATEWAY, "출석 원천 조회 또는 파일 생성에 실패했습니다.")
        }
    }
}

/**
 * 첫 번째는 확정 신청자(사람당 한 행, 확정 신청이 여럿이면 먼저 접수된 신청), 두 번째는 확정 신청 없는 출석 기록(사람의 가장 최근 신청 상태).
 * 출석 기록의 사람 ID가 유형과 맞지 않거나 중복이면 계약 위반으로 실패한다.
 */
internal fun combine(
    programType: SessionType,
    applications: List<ApplicationRow>,
    records: List<ProgramAttendanceRecord>,
    people: Map<Long, AttendancePerson>,
): Pair<List<AttendanceRow>, List<AttendanceRow>> {
    fun ApplicationRow.personId() = if (programType == SessionType.STANDARD) participantId else traineeId
    val attended =
        records.associateBy {
            val id = if (programType == SessionType.STANDARD) it.participantId else it.traineeId
            checkNotNull(id?.takeIf { value -> value > 0 }) { "${AttendanceClient.NAME} 서비스 응답 형식 오류" }
        }
    check(attended.size == records.size) { "${AttendanceClient.NAME} 서비스 중복 출석" }
    val byPerson = applications.groupBy { it.personId() }
    val confirmed =
        byPerson.flatMap { (personId, rows) ->
            val ok = rows.filter { it.status == ApplicationStatus.CONFIRMED }.sortedBy { it.createdAt }
            when {
                ok.isEmpty() -> emptyList()

                // 연수자 미연결 신청은 출석과 연결할 수 없어 신청마다 한 행이고 출석 여부를 비운다
                personId == null -> ok.map { AttendanceRow(null, null, it, null) }

                else -> listOf(AttendanceRow(personId, people[personId], ok.first(), attended[personId]))
            }
        }
    val confirmedIds = confirmed.mapNotNull { it.personId }.toSet()
    val mismatched =
        attended.filterKeys { it !in confirmedIds }.map { (personId, record) ->
            AttendanceRow(personId, people[personId], byPerson[personId]?.maxBy { it.createdAt }, record)
        }
    val order = compareBy<AttendanceRow, Long?>(nullsLast()) { it.personId }
    return confirmed.sortedWith(order) to mismatched.sortedWith(order)
}
