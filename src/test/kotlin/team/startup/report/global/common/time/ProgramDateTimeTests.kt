package team.startup.report.global.common.time

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

class ProgramDateTimeTests {
    @Test
    fun `오프셋 없는 v1·v2 형식은 값 그대로 읽는다`() {
        parseProgramDateTime("2026-10-31 13:00") shouldBe LocalDateTime.of(2026, 10, 31, 13, 0)
        parseProgramDateTime("2026-10-31T13:00") shouldBe LocalDateTime.of(2026, 10, 31, 13, 0)
        parseProgramDateTime(" 2026-10-31T13:00:30 ") shouldBe LocalDateTime.of(2026, 10, 31, 13, 0, 30)
    }

    @Test
    fun `UTC Instant와 오프셋 값은 Asia-Seoul 지역 일시로 바꾼다`() {
        // Expo#196 이후 일반 프로그램 응답 형식
        parseProgramDateTime("2026-10-26T01:00:00Z") shouldBe LocalDateTime.of(2026, 10, 26, 10, 0)
        // 날짜가 넘어가는 경우
        parseProgramDateTime("2026-10-26T15:30:00Z") shouldBe LocalDateTime.of(2026, 10, 27, 0, 30)
        parseProgramDateTime("2026-10-26T10:00:00+09:00") shouldBe LocalDateTime.of(2026, 10, 26, 10, 0)
    }

    @Test
    fun `일시가 아닌 값은 예외다`() {
        assertThrows<DateTimeParseException> { parseProgramDateTime("2026-10-26") }
        assertThrows<DateTimeParseException> { parseProgramDateTime("not-a-date") }
    }
}
