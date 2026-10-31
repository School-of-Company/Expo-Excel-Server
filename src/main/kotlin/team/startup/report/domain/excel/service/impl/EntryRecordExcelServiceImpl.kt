package team.startup.report.domain.excel.service.impl

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.util.UriUtils
import team.startup.report.domain.excel.renderer.EntrySubjects
import team.startup.report.domain.excel.renderer.renderEntryRecords
import team.startup.report.domain.excel.service.EntryRecordExcelService
import team.startup.report.domain.excel.service.ExcelFile
import team.startup.report.global.client.attendance.AttendanceClient
import team.startup.report.global.client.attendance.EntryRecord
import team.startup.report.global.client.attendance.WalkInCount
import team.startup.report.global.client.user.StandardParticipantEntryCard
import team.startup.report.global.client.user.TraineeEntryCard
import team.startup.report.global.client.user.UserClient
import team.startup.report.global.exception.ExpectedException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * GET /excel/entry-records/{expo_id}. 참여 서비스 입장 기록을 끝 페이지까지 읽고 일반 참가자·연수자 이름·직업을 유저 서비스에서
 * 다운로드 시점 값으로 채운다. 한 페이지라도 실패하면 부분 결과로 파일을 만들지 않는다. 유저 서비스가 돌려주지 않은 대상(삭제·다른 행사)은
 * 이름 빈 칸, 직업 미확인이다. 이름·토큰은 로그에 남기지 않는다.
 */
@Service
class EntryRecordExcelServiceImpl(
    private val attendance: AttendanceClient,
    private val user: UserClient,
) : EntryRecordExcelService {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun execute(
        expoId: String,
        from: LocalDate?,
        to: LocalDate?,
    ): ExcelFile {
        valid((from == null) == (to == null), "from과 to는 함께 지정해야 합니다.")
        valid(from == null || to == null || from <= to, "from은 to보다 늦을 수 없습니다.")
        return try {
            val (records, walkIn) = readAll(expoId, from, to)
            val subjects = subjects(expoId, records)
            val timestamp = LocalDateTime.now(SEOUL).format(TIMESTAMP)
            val filename = UriUtils.encode("입장기록_$timestamp.xlsx", Charsets.UTF_8)
            ExcelFile("attachment; filename*=UTF-8''$filename", renderEntryRecords(records, subjects, walkIn))
        } catch (e: ExpectedException) {
            throw e
        } catch (e: Exception) {
            // 메시지는 공급자 이름과 상태만 담는다(원인·URL·본문 없음)
            log.error("입장 기록 엑셀 생성 실패: {} {}", e.javaClass.simpleName, e.message)
            throw ExpectedException(HttpStatus.BAD_GATEWAY, "입장 기록 원천 조회 또는 파일 생성에 실패했습니다.")
        }
    }

    private fun readAll(
        expoId: String,
        from: LocalDate?,
        to: LocalDate?,
    ): Pair<List<EntryRecord>, List<WalkInCount>> {
        val records = mutableListOf<EntryRecord>()
        var walkIn = emptyList<WalkInCount>()
        var page = 0
        while (true) {
            val result =
                attendance.entryRecordPage(expoId, from, to, page, AttendanceClient.PAGE_SIZE)
                    ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾을 수 없습니다.")
            if (page == 0) {
                if (result.totalElements > MAX_RECORDS) {
                    throw ExpectedException(HttpStatus.PAYLOAD_TOO_LARGE, "입장 기록은 최대 ${MAX_RECORDS}건까지 다운로드할 수 있습니다. 기간을 줄여 주세요.")
                }
                walkIn = result.walkIn
            }
            check(result.records.size <= AttendanceClient.PAGE_SIZE) { "Attendance 서비스 응답 형식 오류" }
            records += result.records
            // 기간 중 기록이 늘어도 마지막 응답의 총 건수까지 따라간다. 총 건수에 못 미친 채 빈 페이지가 오면 부분 결과이므로 실패다.
            if (records.size >= result.totalElements) break
            check(result.records.isNotEmpty() && records.size <= MAX_RECORDS) { "Attendance 서비스 응답 형식 오류" }
            page++
        }
        return records to walkIn
    }

    /** 일반 참가자·연수자 ID를 각각 중복 없이 1,000개씩 나눠, 두 목록을 한 요청에 담아 묻는다. 실패는 예외로 올라가 전체 실패다. */
    private fun subjects(
        expoId: String,
        records: List<EntryRecord>,
    ): EntrySubjects {
        fun ids(type: String) =
            records
                .filter { it.subjectType == type }
                .mapNotNull { it.subjectId }
                .distinct()
                .chunked(UserClient.MAX_ENTRY_CARD_IDS)
        val standardIds = ids("STANDARD")
        val traineeIds = ids("TRAINEE")
        val standard = mutableMapOf<Long, StandardParticipantEntryCard>()
        val trainee = mutableMapOf<Long, TraineeEntryCard>()
        repeat(maxOf(standardIds.size, traineeIds.size)) { i ->
            val cards = user.entryCards(expoId, standardIds.getOrElse(i) { emptyList() }, traineeIds.getOrElse(i) { emptyList() })
            cards.standardParticipants.associateByTo(standard) { it.participantId }
            cards.trainees.associateByTo(trainee) { it.traineeId }
        }
        return EntrySubjects(standard, trainee)
    }

    private fun valid(
        condition: Boolean,
        message: String,
    ) {
        if (!condition) throw ExpectedException(HttpStatus.BAD_REQUEST, message)
    }

    private companion object {
        // 힙·Gateway 대기를 확인할 수 없어 보수적으로 둔 값이다(#65, 실측 후 조정). 엑셀 한 시트 행 한도(1,048,576) 안이다
        const val MAX_RECORDS = 300_000L

        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}
