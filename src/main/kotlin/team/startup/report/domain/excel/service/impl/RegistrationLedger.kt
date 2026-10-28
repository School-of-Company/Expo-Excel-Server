package team.startup.report.domain.excel.service.impl

import org.springframework.http.HttpStatus
import org.springframework.web.util.UriUtils
import team.startup.report.domain.excel.presentation.dto.request.DownloadRequest
import team.startup.report.domain.excel.presentation.dto.request.DownloadScope
import team.startup.report.domain.excel.service.ApplicationType
import team.startup.report.domain.excel.service.ExcelFile
import team.startup.report.domain.excel.service.PARTICIPANT_TYPE_LABELS
import team.startup.report.domain.excel.service.informationAnswers
import team.startup.report.domain.excel.service.regionLabel
import team.startup.report.global.client.ApplicationClient
import team.startup.report.global.client.FieldVisit
import team.startup.report.global.client.FieldVisitEvent
import team.startup.report.global.client.PreregistrationRowType
import team.startup.report.global.client.PreregistrationStatus
import team.startup.report.global.client.PreregistrationTarget
import team.startup.report.global.client.RegistrationFilter
import team.startup.report.global.client.StandardParticipantDetail
import team.startup.report.global.exception.ExpectedException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

// 사전·현장 등록 원장(Report #80). 원천은 Application 사전등록 report-targets(#284)와 User 참가자 상세다.
// - APPLICATION 행: User 참가자×회차. 이름·연락처·소속은 User 현재 값, 답변은 등록 당시 제출본이다.
// - RESERVATION 행: User에 저장되지 않은 예약 일행×회차. 예약 ID는 참가자 ID가 아니며 참가자 ID 열은 비운다.
// - 회차 미지정 행: 현재 확정된 사전등록 회차 행이 없는 User FIELD 참가자. 가짜 회차를 만들지 않는다.
//   사전등록을 모두 취소하고 현장 등록한 사람은 취소 행과 회차 미지정 행이 함께 나온다.
//   확정 회차가 "없다"는 것은 조건 없는 전체 스냅샷에서만 알 수 있으므로, 회차·상태·검색 조건이 없는 필터 전체에만 넣는다.

private val KST: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"))
private val FILENAME_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.of("Asia/Seoul"))
private val STATUS_LABELS =
    mapOf(
        PreregistrationStatus.CONFIRMED to "확정",
        PreregistrationStatus.WAITING to "대기",
        PreregistrationStatus.PENDING to "복구 중",
        PreregistrationStatus.CANCELLED to "취소",
    )
private val TRANSITION_LABELS = mapOf("CONFIRMED" to "확정", "CANCELLED" to "취소")
private val SEAT_LABELS = mapOf("TEACHER" to "교원", "OTHER" to "그 외", "UNRECORDED" to "미기록")
private val KIND_LABELS = mapOf("PARTY" to "일행 예약", "COMPANION_ADDITION" to "동행자 추가", "SESSION_ADDITION" to "회차 추가")
private val ACTOR_LABELS = mapOf("USER" to "참가자", "ADMIN" to "관리자", "SYSTEM" to "시스템", "UNKNOWN" to "미기록")
private val JSON: JsonMapper = JsonMapper.builder().build()

// Application 사전등록 report-targets 상한과 같다(선택 key·스냅샷 행 모두). 운영 최대 4회차×(1000+300)=5,200행을 담는다.
// 회차 미지정 FIELD 행은 이 상한에 세지 않고 User 참가자 수만큼 더해진다(시트 행 한도 1,048,576보다 훨씬 작다)
private const val MAX_KEYS = 10_000

private val HEADERS =
    listOf(
        "행 유형",
        "접수 키",
        "신청 ID",
        "예약 ID",
        "참가자 ID",
        "대표/동행",
        "대표자 ID",
        "예약 유형",
        "인원",
        "좌석 구분",
        "이름",
        "직업",
        "소속",
        "본인 지역",
        "본인 연락처",
        "알림 연락처",
        "등록 방식",
        "회차 ID",
        "회차 제목",
        "운영 시작",
        "운영 종료",
        "장소",
        "회차 삭제",
        "상태",
        "확정 시각",
        "취소 시각",
        "취소 주체",
        "대기 경유",
        "답변 출처",
        "미복원 답변 수",
    )

class RegistrationLedger(
    private val application: ApplicationClient,
    private val participants: (String) -> List<StandardParticipantDetail>,
) {
    fun validate(request: DownloadRequest) {
        valid(
            request.applicationIds == null && request.filter == null && request.programId == null && request.traineeId == null,
            "등록 원장에는 접수대장 key 또는 등록 필터만 사용할 수 있습니다.",
        )
        when (request.scope) {
            DownloadScope.SELECTED -> {
                val keys = request.keys
                valid(
                    keys != null && keys.size in 1..MAX_KEYS && keys.distinct().size == keys.size && keys.all { it.length in 1..160 },
                    "접수대장 key는 중복 없이 1–10,000개여야 합니다.",
                )
                valid(request.registrationFilter == null, "선택 key와 등록 필터는 함께 사용할 수 없습니다.")
            }

            DownloadScope.FILTER -> {
                valid(request.keys == null, "전체 범위에는 접수대장 key를 전달할 수 없습니다.")
            }
        }
        request.registrationFilter?.let {
            valid(
                (it.visitDate == null && it.fieldStatus == null) || (it.sessionId == null && it.status == null),
                "방문일·현장 상태는 사전등록 회차·상태와 함께 지정할 수 없습니다.",
            )
            valid(it.sessionId == null || it.sessionId > 0, "회차 ID는 양수여야 합니다.")
            valid(it.status == null || (it.status.isNotEmpty() && it.status.distinct().size == it.status.size), "상태는 중복 없이 1개 이상이어야 합니다.")
            valid(it.q == null || (it.q.isNotBlank() && it.q.length <= 50), "검색어는 1–50자여야 합니다.")
        }
    }

    fun render(
        expoId: UUID,
        request: DownloadRequest,
    ): ExcelFile {
        val filter = request.registrationFilter ?: RegistrationFilter()
        val preKeys = request.keys?.filterNot { it.startsWith("FIELD:") }
        val rows =
            when {
                request.scope == DownloadScope.SELECTED -> if (preKeys!!.isEmpty()) emptyList() else selected(expoId, preKeys)
                filter.visitDate != null || filter.fieldStatus != null -> emptyList()
                else -> filtered(expoId, filter.copy(visitDate = null, fieldStatus = null))
            }
        check(rows.map { it.key }.distinct().size == rows.size)
        rows.forEach { check(it, expoId) }

        val people = participants(expoId.toString()).associateBy { it.participantId }
        check(people.keys.containsAll(rows.mapNotNull { it.participantId }))
        val includeVisits =
            if (request.scope ==
                DownloadScope.SELECTED
            ) {
                request.keys!!.any { it.startsWith("FIELD:") }
            } else {
                filter.sessionId == null && filter.status == null
            }
        val source = if (includeVisits) readFieldVisits(application, expoId, filter.visitDate) else FieldVisitData(emptyList(), emptyList())
        check(people.keys.containsAll(source.items.map { it.participantId }))
        val visits =
            source.items.filter { visit ->
                if (request.scope == DownloadScope.SELECTED) {
                    visit.key in request.keys!!
                } else {
                    val person = people.getValue(visit.participantId)
                    (filter.fieldStatus == null || visit.status == filter.fieldStatus) &&
                        (
                            filter.q == null || person.name.orEmpty().contains(filter.q, ignoreCase = true) ||
                                person.phoneNumber.orEmpty().contains(filter.q)
                        )
                }
            }
        if (request.scope == DownloadScope.SELECTED) valid(visits.size + rows.size == request.keys!!.size, "선택한 현장 등록을 찾을 수 없습니다.")
        val confirmed = rows.filter { it.status == PreregistrationStatus.CONFIRMED }.mapNotNullTo(HashSet()) { it.participantId }
        val includeField = request.scope == DownloadScope.FILTER && filter == RegistrationFilter()
        val datedParticipants = source.items.mapTo(HashSet()) { it.participantId }
        val unassigned =
            if (includeField) {
                people.values
                    .filter {
                        it.applicationType == ApplicationType.FIELD && it.participantId !in confirmed &&
                            it.participantId !in datedParticipants
                    }.sortedBy { it.participantId }
            } else {
                emptyList()
            }
        valid(rows.isNotEmpty() || unassigned.isNotEmpty() || visits.isNotEmpty(), "다운로드 대상이 없습니다.")

        val now = Instant.now()
        val filename = UriUtils.encode("등록원장_${FILENAME_TIME.format(now)}.xlsx", Charsets.UTF_8)
        return ExcelFile(
            "attachment; filename*=UTF-8''$filename",
            renderRegistrationLedger(rows, people, unassigned, now, expoId, request, includeField, visits, source.events),
        )
    }

    private fun selected(
        expoId: UUID,
        keys: List<String>,
    ): List<PreregistrationTarget> {
        val result = application.selectedPreregistrations(expoId, keys)
        check(result.totalElements == keys.size && result.items.size == keys.size)
        check(result.items.map { it.key }.toSet() == keys.toSet())
        val order = keys.withIndex().associate { (index, key) -> key to index }
        return result.items.sortedBy { order.getValue(it.key) }
    }

    private fun filtered(
        expoId: UUID,
        filter: RegistrationFilter,
    ): List<PreregistrationTarget> {
        val initial = application.createPreregistrationSnapshot(expoId, filter)
        check(initial.totalElements >= 0)
        if (initial.totalElements >
            MAX_KEYS
        ) {
            throw ExpectedException(HttpStatus.PAYLOAD_TOO_LARGE, "등록 원장의 사전등록 행은 최대 10,000행까지 다운로드할 수 있습니다.")
        }
        val all = mutableListOf<PreregistrationTarget>()
        val cursors = mutableSetOf<String>()
        var page = initial
        while (true) {
            check(page.snapshotId == initial.snapshotId && page.snapshotAt == initial.snapshotAt && page.expiresAt == initial.expiresAt)
            check(page.totalElements == initial.totalElements && page.items.size <= 500)
            all += page.items
            check(all.size <= initial.totalElements)
            val next = page.nextCursor ?: break
            check(page.items.isNotEmpty() && next.length in 1..128 && cursors.add(next))
            page = application.nextPreregistrationSnapshot(expoId, next)
        }
        check(all.size == initial.totalElements)
        check(
            all.all {
                (filter.sessionId == null || it.session.sessionId == filter.sessionId) &&
                    (filter.status == null || it.status in filter.status)
            },
        )
        if (initial.expiresAt <= Instant.now()) throw ExpectedException(HttpStatus.GONE, "다운로드 스냅샷이 만료되었습니다. 다시 요청해 주세요.")
        return all
    }

    // 행 유형별 식별자 규칙. 예약 행에 참가자 ID가 오거나 key가 다른 행사·다른 ID를 가리키면 원천 불일치다
    private fun check(
        row: PreregistrationTarget,
        expoId: UUID,
    ) {
        check(row.expoId == expoId && row.applicants >= 1 && row.session.sessionId > 0)
        when (row.rowType) {
            PreregistrationRowType.APPLICATION -> {
                check(row.applicationId != null && row.key == "APPLICATION:$expoId:${row.applicationId}")
                check(row.participantId != null && row.participantId > 0 && row.reservationId == null && row.applicants == 1)
                check(row.status == PreregistrationStatus.CONFIRMED || row.status == PreregistrationStatus.CANCELLED)
                row.participant?.takeUnless { it.isNull }?.let { check(it.path("participantId").asLong() == row.participantId) }
            }

            PreregistrationRowType.RESERVATION -> {
                check(row.reservationId != null && row.key == "RESERVATION:$expoId:${row.reservationId}")
                check(row.participantId == null && row.applicationId == null && row.status != PreregistrationStatus.CONFIRMED)
            }
        }
    }
}

private fun renderRegistrationLedger(
    rows: List<PreregistrationTarget>,
    people: Map<Long, StandardParticipantDetail>,
    unassigned: List<StandardParticipantDetail>,
    generatedAt: Instant,
    expoId: UUID,
    request: DownloadRequest,
    includeField: Boolean,
    visits: List<FieldVisit>,
    events: List<FieldVisitEvent>,
): org.apache.poi.xssf.streaming.SXSSFWorkbook {
    val workbook = newWorkbook(500)
    val headerStyle = darkHeaderStyle(workbook)
    val bodyStyle = workbook.createCellStyle().apply { thinBorders() }

    // 등록 답변은 문항 제목 키다(Application 프로그램 답변과 다르다). 열은 전체 행 제목의 합집합
    val answers =
        rows.map { it.registrationSnapshot?.takeUnless(JsonNode::isNull)?.let(::submittedAnswers) } +
            unassigned.map { informationAnswers(it.information.answers, it.information.questions) }
    val answerKeys = answers.flatMapTo(LinkedHashSet()) { it?.values?.keys.orEmpty() }

    val sheet = workbook.createSheet("등록 원장").apply { defaultColumnWidth = 18 }
    val selectedRows = if (request.scope == DownloadScope.SELECTED) mutableMapOf<String, List<String?>>() else null
    sheet.writeRow(0, (HEADERS + answerKeys + FIELD_HEADERS).map(::ledgerCell), headerStyle)
    rows.forEachIndexed { index, row ->
        val submitted = row.registrationSnapshot?.takeUnless(JsonNode::isNull)
        val person = row.participantId?.let(people::getValue)
        val reservation = row.rowType == PreregistrationRowType.RESERVATION
        val fixed =
            listOf(
                if (reservation) "예약(User 미저장)" else "참가자 회차 등록",
                row.key,
                row.applicationId?.toString(),
                row.reservationId,
                row.participantId?.toString(),
                row.representative?.let { if (it) "대표" else "동행" },
                row.representativeId?.toString(),
                row.reservationKind?.let { KIND_LABELS[it] ?: it },
                row.applicants.toString(),
                row.seatCategory?.let { SEAT_LABELS[it] ?: it }
                    ?: row.seatCounts?.let { "교원 ${it.teacher} · 그 외 ${it.other} · 미기록 ${it.unrecorded}" },
                if (person != null) person.name else submitted?.text("name"),
                (if (person != null) person.occupation else submitted?.text("occupation"))?.let { PARTICIPANT_TYPE_LABELS[it] ?: it },
                person?.school?.requiredText(),
                regionLabel(person?.region?.requiredText()),
                // 참가자 행은 User 본인 값만 쓴다. 동행자 행에 대표 제출 번호를 넣지 않는다
                if (person != null) person.phoneNumber else submitted?.text("phoneNumber"),
                person?.notificationPhoneNumber,
                // 사전등록 원천 행은 신청 경로가 사전이다. User 현재 등록 방식(FIELD 재등록 등)으로 바꾸지 않는다
                ApplicationType.PRE.koreanName,
                row.session.sessionId.toString(),
                row.session.title,
                row.session.startedAt?.let(KST::format),
                row.session.endedAt?.let(KST::format),
                row.session.place,
                if (row.session.deleted) "삭제됨" else null,
                STATUS_LABELS.getValue(row.status),
                row.createdAt?.let(KST::format),
                row.cancelledAt?.let(KST::format),
                row.cancellationActorType?.let { ACTOR_LABELS[it] ?: it },
                row.promotedFromWaiting?.let { if (it) "예" else "아니오" } ?: "미기록",
                when {
                    submitted == null -> "미기록(연결 예약 없음)"
                    reservation -> "예약 제출(등록 당시)"
                    row.representative == true -> "본인 제출(등록 당시)"
                    else -> "대표자 제출(등록 당시)"
                },
                answers[index]?.unresolved?.takeIf { it > 0 }?.toString(),
            )
        val cells =
            (
                fixed +
                    answerKeys.map {
                        answers[index]?.values?.get(
                            it,
                        )
                    } + List(FIELD_HEADERS.size) { null }
            ).map { it?.let(::ledgerCell) }
        if (selectedRows != null) selectedRows[row.key] = cells else sheet.writeRow(index + 1, cells, bodyStyle)
    }
    unassigned.forEachIndexed { offset, person ->
        val index = rows.size + offset
        val fixed =
            listOf(
                "현장 등록(회차 미지정)",
                null,
                null,
                null,
                person.participantId.toString(),
                if (person.representativeId == null) "대표" else "동행",
                person.representativeId?.toString(),
                null,
                "1",
                null,
                person.name,
                person.occupation?.let { PARTICIPANT_TYPE_LABELS[it] ?: it },
                person.school.requiredText(),
                regionLabel(person.region.requiredText()),
                person.phoneNumber,
                person.notificationPhoneNumber,
                ApplicationType.FIELD.koreanName,
                null,
                "회차 미지정",
                null,
                null,
                null,
                null,
                "해당 없음(회차 미지정)",
                null,
                null,
                null,
                null,
                "User 등록 답변",
                answers[index]?.unresolved?.takeIf { it > 0 }?.toString(),
            )
        sheet.writeRow(
            index + 1,
            (
                fixed + answerKeys.map { answers[index]?.values?.get(it) } + listOf("UNKNOWN") +
                    List(FIELD_HEADERS.size - 1) { null }
            ).map { it?.let(::ledgerCell) },
            bodyStyle,
        )
    }

    val historySheet = workbook.createSheet("등록 상태 이력").apply { defaultColumnWidth = 20 }
    historySheet.writeRow(0, listOf("접수 키", "순번", "상태", "기록 시각", "실행 유형", "실행 참가자 ID", "실행 관리자 ID"), headerStyle)
    var historyRow = 1
    var untimed = 0
    rows.forEach { row ->
        row.history.forEachIndexed { order, it ->
            if (it.createdAt == null) untimed++
            historySheet.writeRow(
                historyRow++,
                listOf(
                    row.key,
                    (order + 1).toString(),
                    TRANSITION_LABELS[it.status] ?: it.status,
                    it.createdAt?.let(KST::format) ?: "시각 미기록",
                    ACTOR_LABELS[it.actorType] ?: it.actorType,
                    it.actorParticipantId?.toString(),
                    it.actorId?.toString(),
                ).map { value -> value?.let(::ledgerCell) },
                bodyStyle,
            )
        }
    }
    writeFieldVisits(
        workbook,
        visits,
        events,
        people,
        HEADERS.size,
        answerKeys.size,
        rows.size + unassigned.size,
        historyRow,
        headerStyle,
        bodyStyle,
        selectedRows,
    )
    if (selectedRows !=
        null
    ) {
        request.keys!!.forEachIndexed { index, key -> sheet.writeRow(index + 1, selectedRows.getValue(key), bodyStyle) }
    }

    val filter = request.registrationFilter
    val metaSheet = workbook.createSheet("메타").apply { defaultColumnWidth = 30 }
    listOf(
        "항목" to "값",
        "생성 시각(KST)" to KST.format(generatedAt),
        "시간대" to "Asia/Seoul(KST). 회차 일정은 현재 저장된 회차 정의 값(신청 당시 값 아님)",
        "행사 ID" to expoId.toString(),
        "범위" to request.scope.name,
        "선택 key 수" to request.keys?.size?.toString(),
        "회차 조건" to filter?.sessionId?.toString(),
        "상태 조건" to (filter?.status?.joinToString(",") ?: "전체"),
        "검색어" to filter?.q,
        "방문일 조건" to filter?.visitDate?.toString(),
        "현장 상태 조건" to filter?.fieldStatus?.name,
        "방문일 현장 등록 행 수" to visits.size.toString(),
        "현장 집계 기준" to "참가자×방문일. 활성 완료는 ACTIVE이며 cancellationPending·deliveryPending이 모두 false. 입장 인원 아님. UNKNOWN은 날짜별 집계 제외",
        "행 기준" to "사전등록 참가자×회차, User 미저장 예약 일행×회차, FIELD 참가자×방문일 각 1행. 명시적 방문일 원천과 확정 회차가 없는 과거 FIELD는 UNKNOWN",
        "행 상한" to "사전등록·예약 행 최대 10,000(Application 원천과 같음). 회차 미지정 행은 이 상한과 별도로 더해짐",
        "참가자 회차 등록 행 수" to rows.count { it.rowType == PreregistrationRowType.APPLICATION }.toString(),
        "예약 행 수" to rows.count { it.rowType == PreregistrationRowType.RESERVATION }.toString(),
        "회차 미지정 현장 등록 행 수" to unassigned.size.toString(),
        "회차 미지정 행 포함" to
            if (includeField) "포함(조건 없는 필터 전체)" else "제외(선택·회차·상태·검색 조건이 있으면 회차 없음을 판정할 수 없음)",
        "상태 이력 행 수" to (historyRow - 1 + events.count { event -> visits.any { it.id == event.registrationId } }).toString(),
        "시각 미기록 이력 수" to untimed.toString(),
        "제출본 미기록 행 수" to rows.count { it.registrationSnapshot == null || it.registrationSnapshot.isNull }.toString(),
        "미복원 답변 수" to answers.sumOf { it?.unresolved ?: 0 }.toString(),
        "ID 구분" to "예약 ID는 참가자 ID가 아니다. 예약 행은 User 미저장이라 참가자 ID·알림 연락처가 없고 이름·연락처는 제출 당시 대표 값",
        "연락처" to "본인 연락처는 참가자 본인 번호, 알림 연락처는 문자 수신 번호(동행자는 대표자 번호)",
        "대기 순번·예약 접수 시각" to "원천 미제공(열 없음)",
    ).forEachIndexed { index, (key, value) ->
        metaSheet.writeRow(index, listOf(key, value?.let(::ledgerCell)), if (index == 0) headerStyle else bodyStyle)
    }
    return workbook
}

// informationJson은 제목 키 JSON 문자열이다(객체로 와도 받는다). 형식이 깨졌으면 정상 미응답처럼 보이지 않게 실패한다
private fun submittedAnswers(snapshot: JsonNode) =
    snapshot.path("informationJson").let { raw ->
        val answers = if (raw.isString) JSON.readTree(raw.stringValue()) else raw
        check(answers.isObject || answers.isMissingNode || answers.isNull)
        informationAnswers(if (answers.isObject) answers else JSON.createObjectNode(), snapshot.get("questions"))
    }

private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isString }?.stringValue()

private fun valid(
    condition: Boolean,
    message: String,
) {
    if (!condition) throw ExpectedException(HttpStatus.BAD_REQUEST, message)
}
