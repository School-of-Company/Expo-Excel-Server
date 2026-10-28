package team.startup.report.domain.excel.service

import team.startup.report.global.common.time.parseProgramDateTime
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 엑셀 원천 데이터 조회. 정렬은 v1과 같게 ID(신청 ID) 오름차순이어야 한다.
 * 반환값 null은 대상(행사·프로그램·연수자)이 없다는 뜻이고, v1의 대상별 오류 응답으로 바뀐다.
 */
interface ReportSource {
    fun standardParticipants(expoId: String): List<StandardParticipant>?

    fun trainees(expoId: String): List<Trainee>?

    /** 프로그램이 없거나 [expoId] 소속이 아니면 null */
    fun programParticipants(
        expoId: String,
        programId: Long,
    ): List<ProgramParticipant>?

    fun traineeAttendance(traineeId: Long): TraineeAttendance?
}

enum class ApplicationType(
    val koreanName: String,
) {
    PRE("사전 등록"),
    FIELD("현장 등록"),
}

enum class TrainingCategory { ESSENTIAL, CHOICE }

/**
 * ReportSource 응답 DTO. 엑셀 한 행의 원천 데이터. 동적 답변(information, surveyAnswer)은 키가 문항 제목이고 순서가 보존된 맵이다.
 * 공급자 답변은 [informationAnswers]·[idKeyedAnswers]로 제출 당시 문항 스냅샷 기준의 제목·표시 문자열로 바꿔 넣는다.
 */
data class StandardParticipant(
    val name: String?,
    val phoneNumber: String?,
    val personalInformationStatus: Boolean?,
    val applicationType: ApplicationType,
    val information: Map<String, Any?>,
    val surveyAnswer: Map<String, Any?>,
)

data class Trainee(
    val name: String?,
    val trainingId: String?,
    val phoneNumber: String?,
    val applicationType: ApplicationType,
    val information: Map<String, String?>,
    // 신청 순서(applicationId 오름차순)
    val programs: List<TrainingProgram>,
    val school: String? = null,
    val notificationPhoneNumber: String? = null,
)

data class TrainingProgram(
    val id: Long,
    val title: String?,
    val category: TrainingCategory?,
    // Expo 저장 형식 "yyyy-MM-dd HH:mm", v1 형식 "yyyy-MM-ddTHH:mm" 모두 허용. 오프셋이 붙은 값은 KST로 바꾼다
    val startedAt: String,
    val endedAt: String,
) {
    val startDateTime: LocalDateTime get() = parseProgramDateTime(startedAt)
    val endDateTime: LocalDateTime get() = parseProgramDateTime(endedAt)
    val startDate: LocalDate get() = startDateTime.toLocalDate()
}

data class ProgramParticipant(
    val name: String?,
    val phoneNumber: String?,
    val personalInformationStatus: Boolean?,
    // 신청 관리 다운로드(PROGRAM 양식)만 채운다. null이면 v1 열만 쓴다(GET 명단의 레거시 신청에는 폼 답변·경로가 없다)
    val application: ProgramApplicationColumns? = null,
)

/** 회차 신청 열(Report #76). [answers]는 [idKeyedAnswers]로 바꾼 제출 당시 폼 답변(문항 ID 키)이다. */
data class ProgramApplicationColumns(
    val affiliation: String?,
    val occupation: String?,
    val admissionRoute: String?,
    val appliedAt: String,
    val answers: Map<String, String?>,
)

data class TraineeAttendance(
    val expoTitle: String?,
    val traineeName: String?,
    val trainingId: String?,
    val information: Map<String, Any?>,
    // 신청 순서(applicationId 오름차순)
    val programs: List<TrainingProgram>,
    val school: String? = null,
    val location: String? = null,
)
