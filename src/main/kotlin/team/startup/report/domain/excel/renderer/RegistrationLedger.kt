package team.startup.report.domain.excel.renderer

import org.springframework.http.HttpStatus
import org.springframework.web.util.UriUtils
import team.startup.report.domain.excel.presentation.dto.request.DownloadRequest
import team.startup.report.domain.excel.presentation.dto.request.DownloadScope
import team.startup.report.domain.excel.service.ExcelFile
import team.startup.report.domain.excel.service.impl.valid
import team.startup.report.global.client.application.ApplicationClient
import team.startup.report.global.client.application.PreregistrationRowType
import team.startup.report.global.client.application.PreregistrationStatus
import team.startup.report.global.client.application.PreregistrationTarget
import team.startup.report.global.client.application.RegistrationFilter
import team.startup.report.global.client.user.ApplicationType
import team.startup.report.global.client.user.StandardParticipantDetail
import team.startup.report.global.common.time.SEOUL
import team.startup.report.global.common.time.TIMESTAMP
import team.startup.report.global.exception.ExpectedException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

// 사전·현장 등록 원장(Report #80). 원천은 Application 사전등록 report-targets(#284)와 User 참가자 상세다.
// - APPLICATION 행: User 참가자×회차. 이름·연락처·소속은 User 현재 값, 답변은 등록 당시 제출본이다.
// - RESERVATION 행: User에 저장되지 않은 예약 일행×회차. 예약 ID는 참가자 ID가 아니며 참가자 ID 열은 비운다.
// - 회차 미지정 행: 현재 확정된 사전등록 회차 행이 없는 User FIELD 참가자. 가짜 회차를 만들지 않는다.
//   사전등록을 모두 취소하고 현장 등록한 사람은 취소 행과 회차 미지정 행이 함께 나온다.
//   확정 회차가 "없다"는 것은 조건 없는 전체 스냅샷에서만 알 수 있으므로, 회차·상태·검색 조건이 없는 필터 전체에만 넣는다.

private val FILENAME_TIME: DateTimeFormatter = TIMESTAMP.withZone(SEOUL)

// Application 사전등록 report-targets 상한과 같다(선택 key·스냅샷 행 모두). 운영 최대 4회차×(1000+300)=5,200행을 담는다.
// 회차 미지정 FIELD 행은 이 상한에 세지 않고 User 참가자 수만큼 더해진다(시트 행 한도 1,048,576보다 훨씬 작다)
private const val MAX_KEYS = 10_000

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
        val selectedKeys = request.keys?.toHashSet()
        val visits =
            source.items.filter { visit ->
                if (request.scope == DownloadScope.SELECTED) {
                    visit.key in selectedKeys!!
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
        return ExcelFile.attachment(
            filename,
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
