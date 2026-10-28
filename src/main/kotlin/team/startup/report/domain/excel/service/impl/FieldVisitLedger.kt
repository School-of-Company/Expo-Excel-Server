package team.startup.report.domain.excel.service.impl

import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import team.startup.report.domain.excel.renderer.ledgerCell
import team.startup.report.domain.excel.renderer.writeRow
import team.startup.report.domain.excel.service.PARTICIPANT_TYPE_LABELS
import team.startup.report.domain.excel.service.regionLabel
import team.startup.report.global.client.ApplicationClient
import team.startup.report.global.client.FieldVisit
import team.startup.report.global.client.FieldVisitEvent
import team.startup.report.global.client.FieldVisitEventType
import team.startup.report.global.client.FieldVisitStatus
import team.startup.report.global.client.StandardParticipantDetail
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

internal val FIELD_HEADERS = listOf("방문일", "FIELD 상태", "세대", "현장 등록 ID", "취소 참가자 ID", "취소 전달 대기", "등록 전달 대기")
private val FIELD_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"))

internal data class FieldVisitData(
    val items: List<FieldVisit>,
    val events: List<FieldVisitEvent>,
)

internal fun readFieldVisits(
    application: ApplicationClient,
    expoId: UUID,
    date: LocalDate?,
): FieldVisitData {
    val items = mutableListOf<FieldVisit>()
    val events = mutableListOf<FieldVisitEvent>()
    val cursors = mutableSetOf<UUID>()
    var cursor: UUID? = null
    do {
        val page = application.fieldVisits(expoId, date, cursor)
        check(page.items.size <= 100)
        check(
            page.items
                .map { it.id }
                .distinct()
                .size == page.items.size,
        )
        page.items.forEach {
            check(it.expoId == expoId && it.participantId > 0 && it.generation > 0 && (date == null || it.visitDate == date))
            check((it.status == FieldVisitStatus.CANCELLED) == (it.cancelledAt != null))
        }
        val byId = page.items.associateBy { it.id }
        check(page.events.all { it.registrationId in byId && it.generation in 1..byId.getValue(it.registrationId).generation })
        items += page.items
        events += page.events
        cursor = page.nextCursor
        check(cursor == null || (page.items.isNotEmpty() && cursors.add(cursor)))
    } while (cursor != null)
    check(items.map { it.id }.distinct().size == items.size)
    check(items.map { it.participantId to it.visitDate }.distinct().size == items.size)
    check(events.map { Triple(it.registrationId, it.generation, it.eventType) }.distinct().size == events.size)
    return FieldVisitData(items, events)
}

internal fun writeFieldVisits(
    workbook: SXSSFWorkbook,
    visits: List<FieldVisit>,
    events: List<FieldVisitEvent>,
    people: Map<Long, StandardParticipantDetail>,
    fixedColumns: Int,
    answerColumns: Int,
    offset: Int,
    historyOffset: Int,
    header: CellStyle,
    body: CellStyle,
    selectedRows: MutableMap<String, List<String?>>?,
) {
    val sheet = workbook.getSheet("등록 원장")
    visits.forEachIndexed { index, visit ->
        val person = people.getValue(visit.participantId)
        val values = MutableList<String?>(fixedColumns + answerColumns) { null }
        values[0] = "현장 방문일 등록"
        values[1] = visit.key
        values[4] = visit.participantId.toString()
        values[5] = if (person.representativeId == null) "대표" else "동행"
        values[6] = person.representativeId?.toString()
        values[8] = "1"
        values[10] = person.name
        values[11] = person.occupation?.let { PARTICIPANT_TYPE_LABELS[it] ?: it }
        values[12] = person.school.requiredText()
        values[13] = regionLabel(person.region.requiredText())
        values[14] = person.phoneNumber
        values[15] = person.notificationPhoneNumber
        values[16] = "현장 등록"
        values[23] =
            when {
                visit.cancellationPending -> "취소 전달 대기"
                visit.deliveryPending -> "등록 전달 대기"
                visit.status == FieldVisitStatus.CANCELLED -> "취소"
                else -> "등록 완료"
            }
        values[24] = if (visit.deliveryPending || visit.cancellationPending) null else FIELD_TIME.format(visit.registeredAt)
        values[25] = visit.cancelledAt?.let(FIELD_TIME::format)
        values[26] = visit.cancelledBy?.let { "참가자" }
        values[28] = "User 본인 현재 프로필(방문일 등록 답변 원천 없음)"
        val cells =
            (
                values +
                    listOf(
                        visit.visitDate.toString(),
                        visit.status.name,
                        visit.generation.toString(),
                        visit.id.toString(),
                        visit.cancelledBy?.toString(),
                        visit.cancellationPending.toString(),
                        visit.deliveryPending.toString(),
                    )
            ).map { it?.let(::ledgerCell) }
        if (selectedRows != null) selectedRows[visit.key] = cells else sheet.writeRow(offset + index + 1, cells, body)
    }
    val ids = visits.associateBy { it.id }
    val fieldHistory = workbook.createSheet("현장 등록 세대 이력")
    fieldHistory.writeRow(0, listOf("접수 키", "세대", "이벤트", "기록 시각", "실행 참가자 ID", "작업 ID"), header)
    events
        .filter {
            it.registrationId in ids
        }.sortedWith(compareBy({ it.registrationId.toString() }, { it.generation }, { it.occurredAt }, { it.eventType }))
        .forEachIndexed {
            index,
            event,
            ->
            val visit = ids.getValue(event.registrationId)
            val status = if (event.eventType == FieldVisitEventType.REGISTERED) "등록" else "취소"
            fieldHistory.writeRow(
                index + 1,
                listOf(
                    visit.key,
                    event.generation.toString(),
                    event.eventType.name,
                    FIELD_TIME.format(event.occurredAt),
                    event.actorParticipantId?.toString(),
                    event.operationId.toString(),
                ).map {
                    it?.let(::ledgerCell)
                },
                body,
            )
            workbook
                .getSheet(
                    "등록 상태 이력",
                ).writeRow(
                    historyOffset + index,
                    listOf(
                        visit.key,
                        event.generation.toString(),
                        status,
                        FIELD_TIME.format(event.occurredAt),
                        if (event.actorParticipantId == null) "미기록" else "참가자",
                        event.actorParticipantId?.toString(),
                        null,
                    ).map {
                        it?.let(::ledgerCell)
                    },
                    body,
                )
        }
    val counts = workbook.createSheet("방문일별 현장 인원")
    counts.writeRow(0, listOf("방문일", "참가자 방문일 건수", "활성 등록 완료 인원", "취소 인원", "등록 전달 대기 인원", "취소 전달 대기 인원"), header)
    visits.groupBy { it.visitDate }.toSortedMap().entries.forEachIndexed { index, (date, rows) ->
        counts.writeRow(
            index + 1,
            listOf(
                date.toString(),
                rows.size.toString(),
                rows
                    .count {
                        it.status == FieldVisitStatus.ACTIVE &&
                            !it.deliveryPending &&
                            !it.cancellationPending
                    }.toString(),
                rows
                    .count {
                        it.status == FieldVisitStatus.CANCELLED
                    }.toString(),
                rows.count { it.deliveryPending }.toString(),
                rows.count { it.cancellationPending }.toString(),
            ),
            body,
        )
    }
}
