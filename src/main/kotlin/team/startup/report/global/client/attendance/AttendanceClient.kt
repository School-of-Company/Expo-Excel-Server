package team.startup.report.global.client.attendance

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import team.startup.report.global.client.ClientResilience
import team.startup.report.global.client.SessionType
import team.startup.report.global.client.fetchOrNull
import team.startup.report.global.client.fetchRequired
import team.startup.report.global.exception.ExpectedException
import java.time.LocalDate

/**
 * Expo-Attendance-Server 내부 조회(Attendance #150). 입장·재입장·퇴장 이력을 발생 시각 오름차순 페이지로 받는다.
 * 주소·토큰은 선택 설정이라 [api]가 null이면(미설정) 호출 시 503이다. 이름·연락처는 공급자가 주지 않는다.
 */
class AttendanceClient(
    private val api: AttendanceApi?,
    private val resilience: ClientResilience = ClientResilience(NAME),
) {
    /** 행사가 없으면 null(공급자 404). 기간 검증은 호출자가 먼저 하므로 공급자 400은 계약 오류로 보고 실패다. */
    fun entryRecordPage(
        expoId: String,
        from: LocalDate?,
        to: LocalDate?,
        page: Int,
        size: Int,
    ): EntryRecordPage? {
        val client = api ?: throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "입장 기록 서비스 연결이 설정되지 않았습니다.")
        return resilience.read.fetchOrNull { client.entryRecordPage(expoId, from?.toString(), to?.toString(), page, size) }
    }

    /**
     * 프로그램 한 회차의 실제 출석(Attendance #194). 일반은 참가자 ID, 연수는 연수자 ID로 온다. 미출석자는 목록에 없고 출석 0명은 빈 목록이다.
     * 공급자 실패는 빈 목록으로 바꾸지 않고 예외다.
     */
    fun programAttendances(
        programType: SessionType,
        programId: Long,
    ): List<ProgramAttendanceRecord> {
        val client = api ?: throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "출석 서비스 연결이 설정되지 않았습니다.")
        return resilience.read.fetchRequired { client.programAttendances(programType.name.lowercase(), programId) }
    }

    companion object {
        const val NAME = "Attendance"

        // Attendance #150 계약의 size 최대는 5000이다. 한 번에 받는 양은 이보다 작게 둔다.
        const val PAGE_SIZE = 2000
    }
}

interface AttendanceApi {
    // from·to가 null이면 쿼리에서 빠진다. 날짜는 ISO(yyyy-MM-dd) 문자열로 넘긴다(기본 변환은 지역 형식이다)
    @GetMapping("/internal/expos/{expoId}/entry-records")
    fun entryRecordPage(
        @PathVariable("expoId") expoId: String,
        @RequestParam("from", required = false) from: String?,
        @RequestParam("to", required = false) to: String?,
        @RequestParam("page") page: Int,
        @RequestParam("size") size: Int,
    ): EntryRecordPage?

    @GetMapping("/internal/program-attendances/{type}/{programId}")
    fun programAttendances(
        @PathVariable("type") type: String,
        @PathVariable("programId") programId: Long,
    ): List<ProgramAttendanceRecord>?
}

/** 이름·연락처 없이 대상 ID만 싣는다. 종이 QR은 [subjectId]가 null이다. 구분 값은 모르는 값이 와도 읽도록 문자열로 받는다. */
data class EntryRecord(
    // 서버 로컬 시각 "yyyy-MM-dd'T'HH:mm:ss"
    val occurredAt: String,
    // ENTER / RE_ENTER / EXIT
    val eventType: String,
    // STANDARD / TRAINEE / PAPER_QR
    val subjectType: String,
    val subjectId: Long? = null,
    val category: String? = null,
    val paperCode: String? = null,
    val scanner: String? = null,
)

data class WalkInCount(
    val date: String,
    val category: String,
    val count: Long,
)

/** [walkIn]은 첫 페이지(page = 0)에서만 채워지고 나머지 페이지는 빈 배열이다. */
data class EntryRecordPage(
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val records: List<EntryRecord>,
    val walkIn: List<WalkInCount> = emptyList(),
)

/**
 * 회차 실제 출석 한 건. [attendanceDate]는 첫 입실 스캔의 실제 날짜이고 없으면(#194 이전 공급자) 읽기에 실패한다.
 * 퇴실 시각(leaveTime)은 받지 않는다: 퇴실·이수 결과를 출력하지 않는다.
 */
data class ProgramAttendanceRecord(
    val participantId: Long? = null,
    val traineeId: Long? = null,
    val attendanceDate: LocalDate,
    // "HH:mm"
    val entryTime: String,
)
