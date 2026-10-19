package team.startup.report.global.client

import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClient
import team.startup.report.global.exception.ExpectedException
import java.time.LocalDate
import java.util.Optional

/**
 * Expo-Attendance-Server 내부 조회(Attendance #150). 입장·재입장·퇴장 이력을 발생 시각 오름차순 페이지로 받는다.
 * 주소·토큰은 선택 설정이라 [http]가 null이면(미설정) 호출 시 503이다. 이름·연락처는 공급자가 주지 않는다.
 */
class AttendanceClient(
    private val http: RestClient?,
) {
    /** 행사가 없으면 null(공급자 404). 기간 검증은 호출자가 먼저 하므로 공급자 400은 계약 오류로 보고 실패다. */
    fun entryRecordPage(
        expoId: String,
        from: LocalDate?,
        to: LocalDate?,
        page: Int,
        size: Int,
    ): EntryRecordPage? {
        val client = http ?: throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "입장 기록 서비스 연결이 설정되지 않았습니다.")
        return client
            .get()
            .uri {
                it
                    .path("/internal/expos/{expoId}/entry-records")
                    .queryParamIfPresent("from", Optional.ofNullable(from))
                    .queryParamIfPresent("to", Optional.ofNullable(to))
                    .queryParam("page", page)
                    .queryParam("size", size)
                    .build(expoId)
            }.fetchOrNull(NAME)
    }

    companion object {
        const val NAME = "Attendance"

        // Attendance #150 계약의 size 최대는 5000이다. 한 번에 받는 양은 이보다 작게 둔다.
        const val PAGE_SIZE = 2000
    }
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
