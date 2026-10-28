package team.startup.report.global.common.time

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

val PROGRAM_SCHEDULE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

// Expo 프로그램 일시: v2 저장 형식 "yyyy-MM-dd HH:mm", v1 형식 "yyyy-MM-ddTHH:mm"은 그대로,
// Expo#196 이후 일반 프로그램 응답의 UTC Instant("...Z")·오프셋 값은 Asia/Seoul 지역 일시로 바꾼다
fun parseProgramDateTime(value: String): LocalDateTime {
    val parsed = DateTimeFormatter.ISO_DATE_TIME.parse(value.trim().replace(' ', 'T'))
    return if (parsed.isSupported(ChronoField.OFFSET_SECONDS)) {
        OffsetDateTime.from(parsed).atZoneSameInstant(SEOUL).toLocalDateTime()
    } else {
        LocalDateTime.from(parsed)
    }
}

// 신청 스냅샷처럼 형식을 보장할 수 없는 값용. 읽지 못하면 null이라 호출부가 원문 처리로 되돌아간다
fun parseProgramDateTimeOrNull(value: String): LocalDateTime? =
    try {
        parseProgramDateTime(value)
    } catch (_: DateTimeException) {
        null
    }
