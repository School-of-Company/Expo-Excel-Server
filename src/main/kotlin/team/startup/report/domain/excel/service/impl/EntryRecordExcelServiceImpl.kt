package team.startup.report.domain.excel.service.impl

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.util.UriUtils
import team.startup.report.domain.excel.renderer.EntryNames
import team.startup.report.domain.excel.renderer.renderEntryRecords
import team.startup.report.domain.excel.service.EntryRecordExcelService
import team.startup.report.domain.excel.service.ExcelFile
import team.startup.report.global.client.AttendanceClient
import team.startup.report.global.client.EntryRecord
import team.startup.report.global.client.UserClient
import team.startup.report.global.client.WalkInCount
import team.startup.report.global.exception.ExpectedException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * GET /excel/entry-records/{expo_id}. 참여 서비스 입장 기록을 끝 페이지까지 읽고 일반 참가자·연수자 이름을 유저 서비스에서 채운다.
 * 한 페이지라도 실패하면 부분 결과로 파일을 만들지 않는다. 이름을 찾지 못한 대상은 빈 칸이다. 이름·토큰은 로그에 남기지 않는다.
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
            val names = names(expoId, records)
            val timestamp = LocalDateTime.now(SEOUL).format(TIMESTAMP)
            val filename = UriUtils.encode("입장기록_$timestamp.xlsx", Charsets.UTF_8)
            ExcelFile("attachment; filename*=UTF-8''$filename", renderEntryRecords(records, names, walkIn))
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

    private fun names(
        expoId: String,
        records: List<EntryRecord>,
    ): EntryNames {
        fun ids(type: String) = records.filter { it.subjectType == type }.mapNotNull { it.subjectId }.distinct()
        return EntryNames(
            standard =
                resolveNames(ids("STANDARD")) { batch ->
                    user.standardParticipantNames(expoId, batch)?.associate { it.participantId to it.name }
                },
            trainee =
                resolveNames(ids("TRAINEE")) { batch ->
                    user.traineeNames(expoId, batch)?.associate { it.traineeId to it.name }
                },
        )
    }

    /**
     * 유저 서비스는 요청 ID가 하나라도 없으면 전체를 404로 거절한다(null). 그럴 때는 반으로 나눠 다시 물어 없는 ID만 빈 칸으로 남긴다.
     * 404 외 실패는 예외로 올라가 전체 실패다. 배치당 [MAX_NAME_CALLS_PER_BATCH]번을 넘기면 더 묻지 않고 남은 ID는 빈 칸이다.
     */
    private fun resolveNames(
        ids: List<Long>,
        fetch: (List<Long>) -> Map<Long, String?>?,
    ): Map<Long, String> {
        val result = mutableMapOf<Long, String>()

        ids.chunked(UserClient.MAX_NAME_IDS).forEach { chunk ->
            var calls = 0

            fun resolve(batch: List<Long>) {
                if (batch.isEmpty() || calls >= MAX_NAME_CALLS_PER_BATCH) return
                calls++
                val found = fetch(batch)
                if (found != null) {
                    batch.forEach { id -> found[id]?.let { result[id] = it } }
                } else if (batch.size > 1) {
                    val half = batch.size / 2
                    resolve(batch.subList(0, half))
                    resolve(batch.subList(half, batch.size))
                }
            }
            resolve(chunk)
            if (calls >= MAX_NAME_CALLS_PER_BATCH) {
                log.warn("이름 조회 호출 상한 도달: 배치 {}건 중 일부 이름을 빈 칸으로 둡니다", chunk.size)
            }
        }
        return result
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

        // 꽉 찬 배치(10,000개)에서 없는 ID 하나를 찾는 데 약 2×log2(10,000)+1 ≈ 29번이 든다. 100이면 없는 ID 3개까지는 모두 찾고,
        // 배치 전체가 없는 최악(2n−1 ≈ 2만 번 순차 호출)에서도 다운로드가 시간 초과 전에 끝난다.
        const val MAX_NAME_CALLS_PER_BATCH = 100
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}
