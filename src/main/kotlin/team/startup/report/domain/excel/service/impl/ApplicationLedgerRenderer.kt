package team.startup.report.domain.excel.service.impl

import org.apache.poi.xssf.streaming.SXSSFWorkbook
import team.startup.report.domain.excel.service.PARTICIPANT_TYPE_LABELS
import team.startup.report.domain.excel.service.idKeyedAnswers
import team.startup.report.domain.excel.service.regionLabel
import team.startup.report.global.client.ApplicationFilter
import team.startup.report.global.client.ApplicationRow
import team.startup.report.global.client.ApplicationStatus
import team.startup.report.global.client.SessionType
import team.startup.report.global.client.StandardParticipantDetail
import team.startup.report.global.client.TraineeDetailItem
import team.startup.report.global.common.time.PROGRAM_SCHEDULE
import team.startup.report.global.common.time.parseProgramDateTimeOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// 프로그램·연수 신청 원장(Report #80). 신청 1건 = 1행이고 사람 중복 제거·확정 필터를 하지 않는다.
// 원천에 없는 값(대기 순번, 기록 이전 이력)은 만들지 않고 빈칸과 비고·메타 건수로 구분한다.

private val KST: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"))

// 회차 정의 스냅샷의 운영 일시. 읽지 못하는 값은 예전처럼 원문을 보인다
private fun scheduleText(value: String): String = parseProgramDateTimeOrNull(value)?.format(PROGRAM_SCHEDULE) ?: value.replace('T', ' ')

private val STATUS_LABELS =
    mapOf(
        ApplicationStatus.CONFIRMED to "확정",
        ApplicationStatus.WAITING to "대기",
        ApplicationStatus.CANCELLED to "취소",
    )
private val ROUTE_LABELS = mapOf("PRE_REGISTRATION" to "사전", "ONSITE" to "현장")

/** 메타 시트 값. [filter]·[selected]는 요청 그대로이고 개인정보가 아닌 조회 조건만 담는다(q는 관리자가 입력한 검색어다). */
class LedgerMeta(
    val generatedAt: Instant,
    val expoId: String,
    val scope: String,
    val filter: ApplicationFilter?,
    val selected: Int?,
)

private val LEDGER_HEADERS =
    listOf(
        "원장 유형",
        "신청 ID",
        "접수번호",
        "참가자 ID",
        "연수자 ID",
        "참가자 이름",
        "연수자 이름",
        "직업",
        "소속",
        "본인 지역",
        "대표/동행",
        "대표자 ID",
        "본인 연락처",
        "알림 연락처",
        "회차 ID",
        "회차 제목",
        "운영 시작",
        "운영 종료",
        "장소",
        "신청 경로",
        "상태",
        "신청 시각",
        "최근 취소 시각",
        "최근 승급 시각",
        "이력 비고",
        "미복원 답변 수",
    )

fun renderApplicationLedger(
    rows: List<ApplicationRow>,
    people: Map<Long, StandardParticipantDetail>,
    trainees: Map<Long, TraineeDetailItem>,
    meta: LedgerMeta,
): SXSSFWorkbook {
    val workbook = newWorkbook(500)
    val headerStyle = darkHeaderStyle(workbook)
    val bodyStyle = workbook.createCellStyle().apply { thinBorders() }

    // 신청 답변은 문항 ID 키다. 제목은 그 신청의 제출 당시 스냅샷에서만 찾고 ID를 열 제목으로 쓰지 않는다
    // 같은 제목의 뒤 문항은 "제목 (문항 ID)" 열로 보존한다(idKeyedAnswers)
    val answers = rows.map { idKeyedAnswers(it.answers, it.questions) }
    val answerKeys = answers.flatMapTo(LinkedHashSet()) { it.values.keys }
    var unrecordedHistory = 0

    val sheet = workbook.createSheet("신청 원장").apply { defaultColumnWidth = 18 }
    sheet.writeRow(0, (LEDGER_HEADERS + answerKeys).map(::ledgerCell), headerStyle)
    rows.forEachIndexed { index, row ->
        val person = people.getValue(row.participantId)
        val trainee = row.traineeId?.let(trainees::getValue)
        val history = row.history!!
        val note =
            when {
                history.isEmpty() -> "상태 이력 미기록"
                history.last().status != row.status -> "현재 상태 이력 미기록"
                else -> null
            }
        if (note != null) unrecordedHistory++
        val promoted =
            history.zipWithNext().lastOrNull { (before, after) ->
                before.status == ApplicationStatus.WAITING &&
                    after.status == ApplicationStatus.CONFIRMED
            }
        val definition = row.definition
        sheet.writeRow(
            index + 1,
            listOf(
                if (row.programType == SessionType.TRAINING) "연수 신청" else "프로그램 신청",
                row.applicationId.toString(),
                row.receiptNumber.toString(),
                row.participantId.toString(),
                row.traineeId?.toString(),
                person.name,
                trainee?.name,
                person.occupation?.let { PARTICIPANT_TYPE_LABELS[it] ?: it },
                person.school.requiredText(),
                regionLabel(person.region.requiredText()),
                if (person.representativeId == null) "대표" else "동행",
                person.representativeId?.toString(),
                person.phoneNumber,
                person.notificationPhoneNumber,
                row.programId.toString(),
                definition.path("title").asString(null),
                definition.path("startedAt").asString(null)?.let(::scheduleText),
                definition.path("endedAt").asString(null)?.let(::scheduleText),
                definition.path("place").asString(null),
                row.admissionRoute?.let { ROUTE_LABELS[it] ?: it },
                STATUS_LABELS.getValue(row.status),
                KST.format(row.createdAt),
                history.lastOrNull { it.status == ApplicationStatus.CANCELLED }?.let { KST.format(it.createdAt) },
                promoted?.let { KST.format(it.second.createdAt) },
                note,
                answers[index].unresolved.takeIf { it > 0 }?.toString(),
            ).plus(answerKeys.map { answers[index].values[it] })
                .map { it?.let(::ledgerCell) },
            bodyStyle,
        )
    }

    val historySheet = workbook.createSheet("상태 이력").apply { defaultColumnWidth = 20 }
    historySheet.writeRow(0, listOf("신청 ID", "접수번호", "이력 ID", "상태", "기록 시각"), headerStyle)
    var historyRow = 1
    rows.forEach { row ->
        row.history!!.forEach {
            historySheet.writeRow(
                historyRow++,
                listOf(
                    row.applicationId.toString(),
                    row.receiptNumber.toString(),
                    it.id.toString(),
                    STATUS_LABELS.getValue(it.status),
                    KST.format(it.createdAt),
                ),
                bodyStyle,
            )
        }
    }

    val filter = meta.filter
    val metaSheet = workbook.createSheet("메타").apply { defaultColumnWidth = 30 }
    listOf(
        "항목" to "값",
        "생성 시각(KST)" to KST.format(meta.generatedAt),
        "시간대" to "Asia/Seoul(KST). 신청·이력 시각은 KST로 변환, 운영 일시는 신청 당시 회차 정의 값(오프셋이 붙은 값은 KST로 변환)",
        "행사 ID" to meta.expoId,
        "범위" to meta.scope,
        "선택 신청 수" to meta.selected?.toString(),
        "상태 조건" to filter?.let { f -> f.status?.joinToString(",") ?: "전체" },
        "회차 유형/ID" to filter?.let { listOfNotNull(it.programType?.name, it.programId?.toString()).joinToString("/") },
        "회차 제목 검색" to filter?.search,
        "참가자 검색" to filter?.q,
        "직업" to filter?.occupation,
        "운영일" to filter?.eventDate?.toString(),
        "신청 시각 범위" to filter?.let { listOf(it.from, it.until).joinToString(" ~ ") { t -> t?.let(KST::format).orEmpty() } },
        "정렬" to (filter?.sort?.name ?: "선택 순서"),
        "행 기준" to "프로그램·연수 신청 1건 = 1행(사람 중복 제거 없음). 상태 이력은 '상태 이력' 시트에 모두 보존",
        "신청 행 수" to rows.size.toString(),
        "상태 이력 행 수" to (historyRow - 1).toString(),
        "상태 이력 미기록 신청 수" to unrecordedHistory.toString(),
        "미복원 답변 수" to answers.sumOf { it.unresolved }.toString(),
        "최근 취소/승급 시각" to "현재 신청의 가장 최근 해당 이력. 이력 기록 이전 변경은 빈칸이며 이력 비고로 구분",
        "연락처" to "본인 연락처는 참가자 본인 번호(동행자는 비어 있을 수 있음), 알림 연락처는 문자 수신 번호(동행자는 대표자 번호)",
        "연수자 이름" to "연수자 프로필의 현재 이름. 참가자 이름을 덮어쓰지 않는다",
        "대기 순번" to "원천 미제공(열 없음)",
    ).forEachIndexed { index, (key, value) ->
        metaSheet.writeRow(
            index,
            listOf(key, value?.let(::ledgerCell)),
            if (index ==
                0
            ) {
                headerStyle
            } else {
                bodyStyle
            },
        )
    }
    return workbook
}

// 원장 답변은 줄바꿈을 보존한다. XML에 쓸 수 없는 제어 문자만 빼고 셀 길이 상한(32,767자)으로 자른다
internal fun ledgerCell(value: String): String = value.replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "").take(32767)
