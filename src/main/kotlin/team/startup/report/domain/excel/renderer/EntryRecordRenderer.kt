package team.startup.report.domain.excel.renderer

import org.apache.poi.xssf.streaming.SXSSFWorkbook
import team.startup.report.domain.excel.service.PARTICIPANT_TYPE_LABELS
import team.startup.report.global.client.attendance.EntryRecord
import team.startup.report.global.client.attendance.WalkInCount
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 이름을 채운 입장 기록. 일반 참가자·연수자 이름은 ID별로 찾고, 못 찾으면 빈 칸이다. 이름은 로그에 남기지 않는다. */
class EntryNames(
    val standard: Map<Long, String> = emptyMap(),
    val trainee: Map<Long, String> = emptyMap(),
)

private val OCCURRED_AT_OUTPUT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private val EVENT_LABELS = mapOf("ENTER" to "입장", "RE_ENTER" to "재입장", "EXIT" to "퇴장")
private val SUBJECT_LABELS = mapOf("STANDARD" to "일반 참가자", "TRAINEE" to "연수자", "PAPER_QR" to "종이 QR")

// 알 수 없는 값은 원문 그대로 보여 준다. STUDENT는 이전 값, CHILD는 성인 외 모두다.
private val PARTICIPATION_LABELS =
    PARTICIPANT_TYPE_LABELS +
        mapOf(
            "ADULT" to "성인",
            "STUDENT" to "어린이·학생",
            "CHILD" to "학생",
        )

fun eventLabel(value: String) = EVENT_LABELS[value] ?: value

fun subjectLabel(value: String) = SUBJECT_LABELS[value] ?: value

fun participationLabel(value: String) = PARTICIPATION_LABELS[value] ?: value

/** 시트 "입장 기록"(발생 시각 순)과 "간편입장 집계"(날짜, 참가 유형, 인원). 기록이 없어도 머리글만 있는 두 시트를 만든다. */
fun renderEntryRecords(
    records: List<EntryRecord>,
    names: EntryNames,
    walkIn: List<WalkInCount>,
): SXSSFWorkbook {
    val workbook = newWorkbook(500)
    val headerStyle = darkHeaderStyle(workbook)
    val bodyStyle = workbook.createCellStyle().apply { thinBorders() }

    val sheet = workbook.createSheet("입장 기록").apply { defaultColumnWidth = 20 }
    sheet.writeRow(0, listOf("일시", "구분", "대상 종류", "이름", "종이 QR 구분", "종이 QR 코드", "스캐너"), headerStyle)
    records.forEachIndexed { index, record ->
        sheet.writeRow(
            index + 1,
            listOf(
                occurredAt(record.occurredAt),
                eventLabel(record.eventType),
                subjectLabel(record.subjectType),
                name(record, names),
                record.category?.let(::participationLabel),
                record.paperCode,
                record.scanner,
            ).map { it?.let(::safe) },
            bodyStyle,
        )
    }

    val walkInSheet = workbook.createSheet("간편입장 집계").apply { defaultColumnWidth = 20 }
    walkInSheet.writeRow(0, listOf("날짜", "참가 유형", "인원"), headerStyle)
    walkIn.forEachIndexed { index, count ->
        val row = walkInSheet.createRow(index + 1)
        row.createCell(0).apply {
            setCellValue(safe(count.date))
            cellStyle = bodyStyle
        }
        row.createCell(1).apply {
            setCellValue(safe(participationLabel(count.category)))
            cellStyle = bodyStyle
        }
        row.createCell(2).apply {
            setCellValue(count.count.toDouble())
            cellStyle = bodyStyle
        }
    }
    return workbook
}

private fun name(
    record: EntryRecord,
    names: EntryNames,
): String? {
    val id = record.subjectId ?: return null
    return when (record.subjectType) {
        "STANDARD" -> names.standard[id]
        "TRAINEE" -> names.trainee[id]
        else -> null
    }
}

// 공급자 형식이 달라도 파일은 만들어야 하므로 못 읽으면 원문을 쓴다
private fun occurredAt(value: String): String =
    try {
        LocalDateTime.parse(value).format(OCCURRED_AT_OUTPUT)
    } catch (_: Exception) {
        value
    }
