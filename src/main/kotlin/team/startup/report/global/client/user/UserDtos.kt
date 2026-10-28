package team.startup.report.global.client.user

import team.startup.report.domain.excel.service.ApplicationType
import tools.jackson.databind.JsonNode

/** User `InformationResDto`. 신청 답변은 문항 제목, 설문 답변은 문항 ID가 키다. [questions]는 제출 당시 스냅샷이고 없으면 null. */
data class InformationResDto(
    val answers: JsonNode,
    val questions: JsonNode? = null,
)

data class DetailPage<T>(
    val items: List<T>,
    val nextCursor: Long?,
)

data class StandardParticipantDetail(
    val participantId: Long,
    val name: String?,
    val phoneNumber: String?,
    // 문자 수신 번호. 본인 번호가 없는 동행자는 대표자 번호, 둘 다 없으면 null이다. 본인 전화번호 열에 쓰지 않는다
    val notificationPhoneNumber: String? = null,
    val personalInformationStatus: Boolean?,
    val applicationType: ApplicationType,
    val information: InformationResDto,
    // 설문에 답하지 않았으면 null
    val surveyAnswer: InformationResDto?,
    // 참가자 자기 컬럼. 동행자는 신청 답변 없이 이 값들만 갖고, 대표자 ID로 대표자의 문항 스냅샷을 찾는다
    val occupation: String? = null,
    val representativeId: Long? = null,
    // User #147. 키가 없으면(구버전 응답) null, 값이 없으면 NullNode다. 구버전 응답으로는 엑셀을 만들지 않는다
    val region: JsonNode? = null,
    val school: JsonNode? = null,
)

/** 개인 phoneNumber와 trainingId는 새 연결 연수자에서 null일 수 있다(User #82). 문자 수신 번호는 기존 전화번호 열에 쓰지 않는다. */
data class TraineeDetailItem(
    val traineeId: Long,
    val name: String?,
    val trainingId: String?,
    val phoneNumber: String?,
    val applicationType: ApplicationType,
    val information: InformationResDto,
    val participantId: Long? = null,
    val school: String? = null,
    val registration: InformationResDto? = null,
)

data class TraineeDetail(
    val traineeId: Long,
    val expoId: String,
    val name: String?,
    val trainingId: String?,
    val information: InformationResDto,
    val participantId: Long? = null,
    val school: String? = null,
    val registration: InformationResDto? = null,
)

data class StandardParticipantBrief(
    val participantId: Long,
    val name: String?,
    val phoneNumber: String?,
    val personalInformationStatus: Boolean?,
)

data class StandardParticipantName(
    val participantId: Long,
    val name: String?,
)

data class TraineeName(
    val traineeId: Long,
    val name: String?,
)
