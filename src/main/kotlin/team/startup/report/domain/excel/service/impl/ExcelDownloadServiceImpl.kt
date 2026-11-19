package team.startup.report.domain.excel.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.startup.report.domain.excel.presentation.dto.request.DownloadRequest
import team.startup.report.domain.excel.presentation.dto.request.DownloadScope
import team.startup.report.domain.excel.presentation.dto.request.ExcelTemplate
import team.startup.report.domain.excel.renderer.RegistrationLedger
import team.startup.report.domain.excel.service.ExcelDownloadService
import team.startup.report.domain.excel.service.ExcelFile
import team.startup.report.domain.excel.service.PARTICIPANT_TYPE_LABELS
import team.startup.report.global.client.SessionType
import team.startup.report.global.client.application.ApplicationClient
import team.startup.report.global.client.application.ApplicationFilter
import team.startup.report.global.client.application.ApplicationRow
import team.startup.report.global.client.application.ApplicationStatus
import team.startup.report.global.client.application.ApplicationTarget
import team.startup.report.global.client.expo.ExpoClient
import team.startup.report.global.client.user.UserClient
import team.startup.report.global.common.time.parseProgramDateTimeOrNull
import team.startup.report.global.exception.ExpectedException
import java.time.Instant
import java.util.UUID

@Service
class ExcelDownloadServiceImpl(
    private val application: ApplicationClient,
    private val user: UserClient,
    private val expo: ExpoClient,
) : ExcelDownloadService {
    override fun execute(
        expoId: UUID,
        request: DownloadRequest,
    ): ExcelFile {
        val registrationLedger = request.template == ExcelTemplate.REGISTRATION_LEDGER
        if (registrationLedger) registration.validate(request) else validate(request)
        return wrapExcelFailure(
            { "엑셀 원천 조회 또는 파일 생성에 실패했습니다." },
            HttpStatus.BAD_GATEWAY,
            logFailure = {},
        ) {
            if (expo.expo(expoId.toString()) == null) throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾을 수 없습니다.")
            if (registrationLedger) return@wrapExcelFailure registration.render(expoId, request)
            val rows =
                targets(expoId, request).map { it.application }.let { rows ->
                    if (request.scope == DownloadScope.FILTER && request.template == ExcelTemplate.ATTENDANCE) {
                        rows.filter { it.traineeId == request.traineeId }
                    } else {
                        rows
                    }
                }
            validateTargets(request, rows)
            templates.render(expoId.toString(), request, rows)
        }
    }

    private val templates = DownloadTemplates(user, expo)

    // 사전등록 원장은 별도 원천(Application #284)이라 따로 둔다. 같은 공급자 클라이언트와 실패 규칙을 쓴다
    private val registration = RegistrationLedger(application) { expoId -> user.allStandardParticipants(expoId) }

    private fun validate(request: DownloadRequest) {
        valid(request.keys == null && request.registrationFilter == null, "접수대장 key와 등록 필터는 등록 원장에만 사용할 수 있습니다.")
        when (request.scope) {
            DownloadScope.SELECTED -> {
                val ids = request.applicationIds
                valid(ids != null && ids.size in 1..1000 && ids.distinct().size == ids.size, "선택 신청은 중복 없이 1–1000개여야 합니다.")
                valid(request.filter == null, "선택 신청과 필터는 함께 사용할 수 없습니다.")
            }

            DownloadScope.FILTER -> {
                valid(request.applicationIds == null, "전체 범위에는 선택 신청을 전달할 수 없습니다.")
            }
        }
        request.filter?.let {
            valid(it.programId == null || (it.programId > 0 && it.programType != null), "회차 필터에는 유형과 양수 ID가 필요합니다.")
            valid(it.search == null || (it.search.isNotBlank() && it.search.length <= 200), "검색어는 1–200자여야 합니다.")
            valid(it.from == null || it.until == null || it.from < it.until, "시작일은 종료일보다 앞서야 합니다.")
            valid(it.status == null || (it.status.isNotEmpty() && it.status.distinct().size == it.status.size), "상태는 중복 없이 1개 이상이어야 합니다.")
            valid(it.q == null || (it.q.isNotBlank() && it.q.length <= 50), "참가자 검색어는 1–50자여야 합니다.")
            valid(it.occupation == null || it.occupation in PARTICIPANT_TYPE_LABELS, "알 수 없는 직업입니다.")
            valid(request.template != ExcelTemplate.ATTENDANCE || it.q == null, "개인 출석부에는 참가자 검색어를 지정할 수 없습니다.")
        }
        valid((request.template == ExcelTemplate.PROGRAM) == (request.programId != null), "프로그램 양식에만 회차 ID를 지정해야 합니다.")
        valid((request.template == ExcelTemplate.ATTENDANCE) == (request.traineeId != null), "개인 출석부에만 연수자 ID를 지정해야 합니다.")
        valid(request.programId == null || request.programId > 0, "회차 ID는 양수여야 합니다.")
        valid(request.traineeId == null || request.traineeId > 0, "연수자 ID는 양수여야 합니다.")
        if (request.template == ExcelTemplate.PROGRAM && request.scope == DownloadScope.FILTER) {
            valid(
                request.filter?.programType == SessionType.STANDARD && request.filter.programId == request.programId,
                "일반 프로그램 전체 범위는 같은 단일 회차 필터가 필요합니다.",
            )
        }
        if (request.template == ExcelTemplate.TRAINEE || request.template == ExcelTemplate.ATTENDANCE) {
            valid(request.filter?.programType == null || request.filter.programType == SessionType.TRAINING, "연수 양식에는 연수 회차만 사용할 수 있습니다.")
        }
    }

    internal fun targets(
        expoId: UUID,
        request: DownloadRequest,
    ): List<ApplicationTarget> {
        var expiresAt: Instant? = null
        val items =
            when (request.scope) {
                DownloadScope.SELECTED -> {
                    val ids = request.applicationIds!!
                    val result = application.selectedTargets(expoId, ids)
                    check(result.totalElements == ids.size && result.items.size == ids.size)
                    check(result.items.map { it.application.applicationId }.toSet() == ids.toSet())
                    // 원장은 관리자가 고른 순서로 출력한다. 사람 명단은 render에서 자체 순서로 다시 정렬한다
                    val order = ids.withIndex().associate { (index, id) -> id to index }
                    result.items.sortedBy { order.getValue(it.application.applicationId) }
                }

                DownloadScope.FILTER -> {
                    val supplied = request.filter ?: ApplicationFilter()
                    val filter =
                        if (request.template == ExcelTemplate.TRAINEE || request.template == ExcelTemplate.ATTENDANCE) {
                            supplied.copy(programType = SessionType.TRAINING)
                        } else {
                            supplied
                        }
                    val q = if (request.template == ExcelTemplate.ATTENDANCE) participantName(expoId, request.traineeId!!) else filter.q
                    val initial = application.createTargetSnapshot(expoId, filter.copy(q = q))
                    check(initial.totalElements >= 0)
                    if (initial.totalElements >
                        10_000
                    ) {
                        throw ExpectedException(HttpStatus.PAYLOAD_TOO_LARGE, "전체 신청은 최대 10,000건까지 다운로드할 수 있습니다.")
                    }
                    expiresAt = initial.expiresAt
                    val all = mutableListOf<ApplicationTarget>()
                    val cursors = mutableSetOf<String>()
                    var page = initial
                    while (true) {
                        check(
                            page.snapshotId == initial.snapshotId && page.snapshotAt == initial.snapshotAt &&
                                page.expiresAt == initial.expiresAt,
                        )
                        check(page.totalElements == initial.totalElements && page.items.size <= 500)
                        all += page.items
                        check(all.size <= initial.totalElements)
                        val next = page.nextCursor ?: break
                        check(page.items.isNotEmpty() && next.length in 1..128 && cursors.add(next))
                        page = application.nextTargetSnapshot(expoId, next)
                    }
                    check(all.size == initial.totalElements)
                    check(all.all { target -> matches(filter, target.application) })
                    all
                }
            }
        check(items.map { it.application.applicationId }.distinct().size == items.size)
        check(
            items.all {
                it.expoId == expoId && it.registrationSource.expoId == expoId &&
                    it.registrationSource.participantId == it.application.participantId
            },
        )
        check(items.all { it.application.participantId > 0 && it.application.programId > 0 && it.application.formVersion > 0 })
        check(
            items.all {
                it.application.definition
                    .path("expoId")
                    .asString() == expoId.toString()
            },
        )
        expiresAt?.let {
            if (it <= Instant.now()) throw ExpectedException(HttpStatus.GONE, "다운로드 스냅샷이 만료되었습니다. 다시 요청해 주세요.")
        }
        return items
    }

    // ponytail: Application 스냅샷에 연수자 조건이 없어 본인 이름(q)으로 후보만 줄이고 traineeId는 메모리에서 거른다.
    // 같은 이름을 포함한 신청이 1만 건을 넘으면 여전히 413이다. Application에 traineeId 조건이 생기면 그것으로 바꾼다.
    private fun participantName(
        expoId: UUID,
        traineeId: Long,
    ): String? {
        val participantId = user.trainee(traineeId)?.participantId ?: return null
        val name = user.standardParticipantNames(expoId.toString(), listOf(participantId))?.singleOrNull()?.name
        // q는 부분 일치라 앞 50자만 보내도 본인은 빠지지 않는다.
        return name?.trim()?.take(50)?.ifBlank { null }
    }

    private fun matches(
        filter: ApplicationFilter,
        row: ApplicationRow,
    ): Boolean =
        (filter.programType == null || filter.programType == row.programType) &&
            (filter.programId == null || filter.programId == row.programId) &&
            (filter.groupId == null || filter.groupId == row.groupId) &&
            (filter.applicationId == null || filter.applicationId == row.applicationId) &&
            (filter.status == null || row.status in filter.status) &&
            // q·occupation은 User 원천 조건이라 여기서 다시 확인할 수 없다. eventDate는 신청 당시 운영 시작일이다
            (
                filter.eventDate == null || scheduleDate(row.definition.path("startedAt").asString("")) == filter.eventDate.toString()
            ) &&
            (filter.from == null || row.createdAt >= filter.from) &&
            (filter.until == null || row.createdAt < filter.until) &&
            (
                filter.search == null ||
                    row.definition
                        .path("title")
                        .asString("")
                        .contains(filter.search.trim(), ignoreCase = true)
            )

    // 운영 시작의 KST 날짜("yyyy-MM-dd"). "2026-10-30T23:00:00Z"는 KST로 10/31 08:00이라 "2026-10-31"이다.
    // 날짜만 있거나("2026-10-31") 빈 값처럼 일시로 해석되지 않으면, 이 변경 전과 같이 문자열 앞 10자를 날짜로 쓴다
    // (예: "2026-10-31" → "2026-10-31", "" → "")
    private fun scheduleDate(startedAt: String): String =
        parseProgramDateTimeOrNull(startedAt)?.toLocalDate()?.toString() ?: startedAt.take(10)

    private fun validateTargets(
        request: DownloadRequest,
        rows: List<ApplicationRow>,
    ) {
        valid(rows.isNotEmpty(), "다운로드 대상이 없습니다.")
        if (request.template == ExcelTemplate.PROGRAM) {
            valid(rows.all { it.programType == SessionType.STANDARD && it.programId == request.programId }, "일반 프로그램은 단일 회차만 다운로드할 수 있습니다.")
        }
        if (request.template == ExcelTemplate.TRAINEE || request.template == ExcelTemplate.ATTENDANCE) {
            valid(rows.all { it.programType == SessionType.TRAINING && it.traineeId != null }, "연수자와 연결된 연수 신청만 사용할 수 있습니다.")
        }
        if (request.template == ExcelTemplate.ATTENDANCE) {
            valid(rows.all { it.traineeId == request.traineeId }, "개인 출석부는 지정한 연수자 한 명의 신청만 사용할 수 있습니다.")
        }
        if (request.template == ExcelTemplate.PROGRAM || request.template == ExcelTemplate.ATTENDANCE) {
            valid(rows.all { it.status == ApplicationStatus.CONFIRMED }, "프로그램 명단과 개인 출석부는 확정 신청만 사용할 수 있습니다.")
        }
    }
}
