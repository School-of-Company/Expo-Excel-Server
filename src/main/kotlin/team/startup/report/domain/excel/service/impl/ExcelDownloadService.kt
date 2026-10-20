package team.startup.report.domain.excel.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.startup.report.domain.excel.presentation.DownloadRequest
import team.startup.report.domain.excel.presentation.DownloadScope
import team.startup.report.domain.excel.presentation.ExcelTemplate
import team.startup.report.domain.excel.service.ExcelFile
import team.startup.report.domain.excel.service.ProgramParticipant
import team.startup.report.domain.excel.service.StandardParticipant
import team.startup.report.domain.excel.service.Trainee
import team.startup.report.domain.excel.service.TraineeAttendance
import team.startup.report.domain.excel.service.TrainingCategory
import team.startup.report.domain.excel.service.TrainingProgram
import team.startup.report.domain.excel.service.informationAnswers
import team.startup.report.domain.excel.service.surveyAnswers
import team.startup.report.global.client.ApplicationClient
import team.startup.report.global.client.ApplicationFilter
import team.startup.report.global.client.ApplicationRow
import team.startup.report.global.client.ApplicationStatus
import team.startup.report.global.client.ApplicationTarget
import team.startup.report.global.client.DetailPage
import team.startup.report.global.client.ExpoClient
import team.startup.report.global.client.SessionType
import team.startup.report.global.client.UserClient
import team.startup.report.global.exception.ExpectedException
import java.time.Instant
import java.util.UUID

@Service
class ExcelDownloadService(
    private val application: ApplicationClient,
    private val user: UserClient,
    private val expo: ExpoClient,
) {
    fun execute(
        expoId: UUID,
        request: DownloadRequest,
    ): ExcelFile {
        validate(request)
        return try {
            if (expo.expo(expoId.toString()) == null) throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾을 수 없습니다.")
            val rows =
                targets(expoId, request).map { it.application }.let { rows ->
                    if (request.scope == DownloadScope.FILTER && request.template == ExcelTemplate.ATTENDANCE) {
                        rows.filter { it.traineeId == request.traineeId }
                    } else {
                        rows
                    }
                }
            validateTargets(request, rows)
            render(expoId.toString(), request, rows)
        } catch (e: ExpectedException) {
            throw e
        } catch (_: Exception) {
            throw ExpectedException(HttpStatus.BAD_GATEWAY, "엑셀 원천 조회 또는 파일 생성에 실패했습니다.")
        }
    }

    private fun validate(request: DownloadRequest) {
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

    private fun targets(
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
                    result.items
                }

                DownloadScope.FILTER -> {
                    val supplied = request.filter ?: ApplicationFilter()
                    val filter =
                        if (request.template == ExcelTemplate.TRAINEE || request.template == ExcelTemplate.ATTENDANCE) {
                            supplied.copy(programType = SessionType.TRAINING)
                        } else {
                            supplied
                        }
                    val q = if (request.template == ExcelTemplate.ATTENDANCE) participantName(expoId, request.traineeId!!) else null
                    val initial = application.createTargetSnapshot(expoId, filter, q)
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
            (filter.status == null || filter.status == row.status) &&
            (filter.from == null || row.createdAt >= filter.from) &&
            (filter.until == null || row.createdAt < filter.until) &&
            (
                filter.search == null ||
                    row.definition
                        .path("title")
                        .asString("")
                        .contains(filter.search.trim(), ignoreCase = true)
            )

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

    private fun render(
        expoId: String,
        request: DownloadRequest,
        rows: List<ApplicationRow>,
    ): ExcelFile {
        val ids = rows.map { it.participantId }.toSet()
        val byParticipant = rows.groupBy { it.participantId }
        val byTrainee = rows.groupBy { it.traineeId }
        return when (request.template) {
            ExcelTemplate.STANDARD -> {
                // 대표자를 고르지 않아도 동행자의 열 제목을 찾을 수 있게 거르기 전 전체로 찾는다. 출력 대상은 고른 참가자뿐이다.
                val all = readAll { user.standardParticipantPage(expoId, it) }
                val byId = all.associateBy { it.participantId }
                val people = all.filter { it.participantId in ids }
                check(people.map { it.participantId }.toSet() == ids && people.size == ids.size)
                val result =
                    people.sortedBy { it.participantId }.map {
                        val registration = it.registration(byId).values
                        StandardParticipant(
                            it.name,
                            it.phoneNumber,
                            it.personalInformationStatus,
                            it.applicationType,
                            existingAnswers(registration, byParticipant.getValue(it.participantId)),
                            it.surveyAnswer?.let { survey -> surveyAnswers(survey.answers, survey.questions).values } ?: emptyMap(),
                        )
                    }
                ExcelFile("attachment; filename*=UTF-8''Participant_Information.xlsx", renderStandardParticipants(result))
            }

            ExcelTemplate.PROGRAM -> {
                check(expo.standardProgram(expoId, request.programId!!) != null)
                val people = user.standardParticipantBriefs(expoId, ids.sorted())
                check(people.map { it.participantId }.toSet() == ids && people.size == ids.size)
                ExcelFile(
                    "attachment; filename=\"Program_Participant_Information.xlsx\"",
                    renderProgramParticipants(
                        people
                            .sortedBy {
                                it.participantId
                            }.map { ProgramParticipant(it.name, it.phoneNumber, it.personalInformationStatus) },
                    ),
                )
            }

            ExcelTemplate.TRAINEE -> {
                val traineeIds = rows.map { it.traineeId!! }.toSet()
                val people = readAll { user.traineePage(expoId, it) }.filter { it.traineeId in traineeIds }
                check(people.map { it.traineeId }.toSet() == traineeIds && people.size == traineeIds.size)
                val result =
                    people.sortedBy { it.traineeId }.map { person ->
                        val selected = byTrainee.getValue(person.traineeId)
                        check(selected.all { it.participantId == person.participantId })
                        val registration =
                            person.registration?.let { informationAnswers(it.answers, it.questions).values }.orEmpty() +
                                informationAnswers(person.information.answers, person.information.questions).values
                        Trainee(
                            person.name,
                            person.trainingId,
                            person.phoneNumber,
                            person.applicationType,
                            existingAnswers(registration, selected),
                            programs(selected),
                            person.school,
                        )
                    }
                ExcelFile("attachment; filename=\"Trainee_Information.xlsx\"", renderTrainees(result))
            }

            ExcelTemplate.ATTENDANCE -> {
                val person = user.trainee(request.traineeId!!) ?: error("연수자 없음")
                check(
                    person.traineeId == request.traineeId && person.expoId == expoId &&
                        rows.all { it.participantId == person.participantId },
                )
                val registration =
                    person.registration?.let { informationAnswers(it.answers, it.questions).values }.orEmpty() -
                        setOf("이름", "연수원아이디", "전화번호") + informationAnswers(person.information.answers, person.information.questions).values
                val info = existingAnswers(registration, rows, setOf("학교명", "소속"))
                val programs = programs(rows)
                val attendance = TraineeAttendance(expo.expo(expoId)!!.title, person.name, person.trainingId, info, programs, person.school)
                ExcelFile(
                    "attachment; filename=\"Trainee_Attendance.xlsx\"",
                    renderTraineeAttendance(
                        attendance,
                        programs
                            .map {
                                it.startDate
                            }.distinct()
                            .sorted(),
                    ),
                )
            }
        }
    }

    // 프로그램 답변은 기존 열의 빈 값만 보완한다. 다른 회차에서 값이 충돌하면 임의로 고르지 않는다.
    private fun existingAnswers(
        registration: Map<String, String?>,
        rows: List<ApplicationRow>,
        keys: Set<String> = registration.keys,
    ): Map<String, String?> {
        val answers = rows.map { informationAnswers(it.answers, it.questions).values }
        val result = registration.toMutableMap()
        for (key in keys - setOf("이름", "전화번호", "연수원아이디")) {
            if (result[key] != null) continue
            val values = answers.mapNotNull { it[key] }.distinct()
            valid(values.size <= 1, "서로 다른 회차의 답변이 충돌합니다. 회차 범위를 좁혀 주세요.")
            values.singleOrNull()?.let { result[key] = it }
        }
        return result
    }

    private fun programs(rows: List<ApplicationRow>): List<TrainingProgram> =
        rows.distinctBy { it.programId }.map { row ->
            val definition = row.definition
            check(definition.path("id").asLong() == row.programId)
            check(definition.path("programType").asString() == SessionType.TRAINING.name)
            TrainingProgram(
                row.programId,
                definition.path("title").asString(null),
                definition.path("category").asString(null)?.let(TrainingCategory::valueOf),
                definition.path("startedAt").asString(),
                definition.path("endedAt").asString(),
            ).also {
                check(it.endDateTime > it.startDateTime)
            }
        }

    private fun <T> readAll(fetch: (Long?) -> DetailPage<T>): List<T> {
        val items = mutableListOf<T>()
        var cursor: Long? = null
        do {
            val page = fetch(cursor)
            items += page.items
            val next = page.nextCursor
            check(next == null || (page.items.isNotEmpty() && (cursor == null || next > cursor)))
            cursor = next
        } while (cursor != null)
        return items
    }

    private fun valid(
        condition: Boolean,
        message: String,
    ) {
        if (!condition) throw ExpectedException(HttpStatus.BAD_REQUEST, message)
    }
}
