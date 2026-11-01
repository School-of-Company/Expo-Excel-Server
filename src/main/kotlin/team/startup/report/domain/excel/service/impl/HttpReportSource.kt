package team.startup.report.domain.excel.service.impl

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import team.startup.report.domain.excel.service.MappedAnswers
import team.startup.report.domain.excel.service.ParticipantProfile
import team.startup.report.domain.excel.service.ProgramParticipant
import team.startup.report.domain.excel.service.ReportSource
import team.startup.report.domain.excel.service.StandardParticipant
import team.startup.report.domain.excel.service.Trainee
import team.startup.report.domain.excel.service.TraineeAttendance
import team.startup.report.domain.excel.service.TrainingProgram
import team.startup.report.domain.excel.service.idKeyedAnswers
import team.startup.report.domain.excel.service.informationAnswers
import team.startup.report.domain.excel.service.standardInformation
import team.startup.report.global.client.application.ApplicationClient
import team.startup.report.global.client.application.TrainingApplication
import team.startup.report.global.client.expo.ExpoClient
import team.startup.report.global.client.user.DetailPage
import team.startup.report.global.client.user.InformationResDto
import team.startup.report.global.client.user.StandardParticipantDetail
import team.startup.report.global.client.user.TraineeDetailItem
import team.startup.report.global.client.user.UserClient
import team.startup.report.global.security.ExcelAccessInterceptor
import tools.jackson.databind.JsonNode

/**
 * 공급자 내부 API를 조합한 원천 조회. 행사·프로그램·연수자가 없으면 null(v1 대상별 오류), 공급자 장애는 예외(엔드포인트별 v1 500 감싸기)다.
 * 공급자가 주는 순서만 믿지 않고 ID로 다시 정렬·연결한다. 전화번호·답변·토큰은 로그에 남기지 않는다.
 */
@Component
class HttpReportSource(
    private val user: UserClient,
    private val application: ApplicationClient,
    private val expo: ExpoClient,
) : ReportSource {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun standardParticipants(expoId: String): List<StandardParticipant>? {
        expo.expo(expoId) ?: return null
        val unresolved = Unresolved()
        val all = readAll { user.standardParticipantPage(expoId, it) }
        val byId = all.associateBy { it.participantId }
        return all
            .sortedBy { it.participantId }
            .map {
                StandardParticipant(
                    name = it.name,
                    phoneNumber = it.phoneNumber,
                    personalInformationStatus = it.personalInformationStatus,
                    applicationType = it.applicationType,
                    information = unresolved.add(it.registration(byId)),
                    surveyAnswer =
                        it.surveyAnswer?.let { survey -> unresolved.add(idKeyedAnswers(survey.answers, survey.questions)) } ?: emptyMap(),
                )
            }.also { unresolved.report("standard", expoId) }
    }

    override fun trainees(expoId: String): List<Trainee>? {
        expo.expo(expoId) ?: return null
        val trainees = readAll { user.traineePage(expoId, it) }.sortedBy { it.traineeId }
        val people =
            if (trainees.any {
                    it.participantId != null
                }
            ) {
                readAll { user.standardParticipantPage(expoId, it) }.associateBy { it.participantId }
            } else {
                emptyMap()
            }
        val applications = trainingApplications(trainees.map { it.traineeId }).groupBy { it.traineeId }
        val programs = trainingPrograms(expoId, applications.values.flatten())
        val unresolved = Unresolved()
        return trainees
            .map { trainee ->
                Trainee(
                    name = trainee.name,
                    trainingId = trainee.trainingId,
                    phoneNumber = trainee.phoneNumber,
                    applicationType = trainee.applicationType,
                    information = unresolved.add(trainee.rosterInformation(people)),
                    programs = applications[trainee.traineeId].orEmpty().map { programs.getValue(it.trainingProgramId) },
                    school = trainee.school,
                    notificationPhoneNumber = trainee.participantId?.let { people.getValue(it).notificationPhoneNumber },
                )
            }.also { unresolved.report("trainee", expoId) }
    }

    override fun programParticipants(
        expoId: String,
        programId: Long,
    ): List<ProgramParticipant>? {
        expo.standardProgram(expoId, programId) ?: return null
        // 신청 순서(applicationId 오름차순)
        val applications = application.standardApplications(programId).sortedBy { it.applicationId }
        val participantIds = applications.map { it.participantId }.distinct()
        val participants =
            participantIds
                .chunked(UserClient.MAX_BRIEF_IDS)
                .flatMap { user.standardParticipantBriefs(expoId, it) }
                .associateBy { it.participantId }
        return applications.map {
            val participant = participants[it.participantId] ?: error("${UserClient.NAME} 서비스 응답 누락")
            ProgramParticipant(participant.name, participant.phoneNumber, participant.personalInformationStatus)
        }
    }

    override fun traineeAttendance(traineeId: Long): TraineeAttendance? {
        val trainee = ExcelAccessInterceptor.authorizedTrainee(traineeId) ?: user.trainee(traineeId) ?: return null
        check(trainee.traineeId == traineeId) { "${UserClient.NAME} 서비스 대상 오류" }
        val expoSummary = expo.expo(trainee.expoId) ?: error("연수자의 박람회 없음")
        val applications = trainingApplications(listOf(traineeId))
        val programs = trainingPrograms(trainee.expoId, applications)
        val unresolved = Unresolved()
        return TraineeAttendance(
            expoTitle = expoSummary.title,
            traineeName = trainee.name,
            trainingId = trainee.trainingId,
            information = unresolved.traineeAnswers(trainee.information, trainee.participantId, trainee.registration, attendance = true),
            programs = applications.map { programs.getValue(it.trainingProgramId) },
            school = trainee.school,
            location = expoSummary.location,
        ).also { unresolved.report("attendance", trainee.expoId) }
    }

    /** 신청 ID 오름차순(= 신청 순서) */
    private fun trainingApplications(traineeIds: List<Long>): List<TrainingApplication> =
        traineeIds
            .chunked(ApplicationClient.MAX_TRAINEE_IDS)
            .flatMap { ids ->
                val requested = ids.toSet()
                application.trainingApplications(ids).also { applications ->
                    check(applications.all { it.traineeId in requested }) { "${ApplicationClient.NAME} 서비스 대상 오류" }
                }
            }.distinctBy { it.applicationId }
            .sortedBy { it.applicationId }

    private fun trainingPrograms(
        expoId: String,
        applications: List<TrainingApplication>,
    ): Map<Long, TrainingProgram> {
        val ids = applications.map { it.trainingProgramId }.distinct()
        val programs =
            ids
                .chunked(ExpoClient.MAX_PROGRAM_IDS)
                .flatMap { expo.trainingPrograms(expoId, it) }
                .associate { it.id to TrainingProgram(it.id, it.title, it.category, it.startedAt, it.endedAt) }
        check(programs.keys.containsAll(ids)) { "${ExpoClient.NAME} 서비스 응답 누락" }
        return programs
    }

    /** 커서를 끝(nextCursor = null)까지 따라간다. 커서가 앞으로 가지 않으면 무한 반복 대신 실패한다. */
    private fun <T> readAll(page: (Long?) -> DetailPage<T>): List<T> {
        val items = mutableListOf<T>()
        var cursor: Long? = null
        do {
            val current = page(cursor)
            items += current.items
            val next = current.nextCursor
            check(next == null || (current.items.isNotEmpty() && (cursor == null || next > cursor))) { "${UserClient.NAME} 서비스 커서 오류" }
            cursor = next
        } while (cursor != null)
        return items
    }

    private fun InformationResDto.toAnswers() = informationAnswers(answers, questions)

    /** 제출 당시 문항으로 복원하지 못한 답변 수. 개인정보 없이 건수와 행사 ID만 남긴다. */
    private inner class Unresolved {
        private var count = 0

        fun traineeAnswers(
            information: InformationResDto,
            participantId: Long?,
            registration: InformationResDto?,
            attendance: Boolean = false,
        ): Map<String, String?> {
            // User #85가 같은 행사 본인의 원천만 제공한다. 연결 ID 없는 원천은 소비하지 않는다.
            val registered = if (participantId != null && registration != null) add(registration.toAnswers()) else emptyMap()
            val restored = if (attendance) registered - setOf("이름", "연수원아이디", "전화번호") else registered
            return restored + add(information.toAnswers())
        }

        fun add(mapped: MappedAnswers): Map<String, String?> {
            count += mapped.unresolved
            return mapped.values
        }

        fun report(
            excel: String,
            expoId: String,
        ) {
            if (count > 0) log.warn("엑셀 답변 복원 불가: excel={}, expoId={}, count={}", excel, expoId, count)
        }
    }
}

/**
 * 일반 참가자 신청 폼 열(GET·SELECTED·FILTER 공통). 값은 이 참가자의 답변과 자기 컬럼에서만 가져온다.
 * 동행자는 [all](행사 전체 상세)에서 찾은 대표자의 문항 스냅샷을 제목으로만 쓰고, 대표자 답변은 쓰지 않는다.
 * 대표자가 없거나 region·school 키가 없는 응답(User #147 이전)은 빈칸으로 숨기지 않고 실패한다.
 */
internal fun StandardParticipantDetail.registration(all: Map<Long, StandardParticipantDetail>): MappedAnswers {
    val representative = representativeId?.let { all[it] ?: error("${UserClient.NAME} 서비스 대표자 누락") }
    return standardInformation(
        information.answers,
        // 동행자 스냅샷은 JSON null(NullNode)로 온다
        information.questions?.takeUnless { it.isNull } ?: representative?.information?.questions,
        ParticipantProfile(occupation, region.requiredText(), school.requiredText()),
        companion = representative != null,
    )
}

internal fun JsonNode?.requiredText(): String? {
    check(this != null && (isNull || isString)) { "${UserClient.NAME} 서비스 응답 형식 오류" }
    return if (isNull) null else stringValue()
}

internal fun TraineeDetailItem.rosterInformation(all: Map<Long, StandardParticipantDetail>): MappedAnswers {
    val registered =
        participantId?.let { id ->
            val person = all[id] ?: error("${UserClient.NAME} 서비스 응답 누락")
            person.registration(all)
        } ?: MappedAnswers(emptyMap(), 0)
    val training = informationAnswers(information.answers, information.questions, roster = true)
    return MappedAnswers(registered.values + training.values, registered.unresolved + training.unresolved)
}
