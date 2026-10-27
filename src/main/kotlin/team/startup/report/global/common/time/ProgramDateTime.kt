package team.startup.report.global.common.time

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

// Expo 프로그램 일시: v2 저장 형식 "yyyy-MM-dd HH:mm", v1 형식 "yyyy-MM-ddTHH:mm"은 그대로,
// Expo#196 이후 일반 프로그램 응답의 UTC Instant("...Z")·오프셋 값은 Asia/Seoul 지역 일시로 바꾼다
fun parseProgramDateTime(value: String): LocalDateTime =
    when (
        val parsed =
            DateTimeFormatter.ISO_DATE_TIME.parseBest(
                value.trim().replace(' ', 'T'),
                OffsetDateTime::from,
                LocalDateTime::from,
            )
    ) {
        is OffsetDateTime -> parsed.atZoneSameInstant(SEOUL).toLocalDateTime()
        else -> parsed as LocalDateTime
    }
