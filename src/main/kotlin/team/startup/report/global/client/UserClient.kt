package team.startup.report.global.client

import org.springframework.web.client.RestClient
import org.springframework.web.util.UriBuilder
import team.startup.report.domain.excel.service.ApplicationType
import tools.jackson.databind.JsonNode
import java.net.URI
import java.util.Optional

/**
 * Expo-User-Server 내부 조회(PR #47·#49). 상세 목록은 ID 오름차순 커서 페이지(최대 500)이고, 일괄 조회는 요청 ID를 모두 돌려주거나 404다.
 * 전화번호는 응답 본문으로만 오가고 URL에 넣지 않는다.
 */
class UserClient(
    private val http: RestClient,
) {
    fun standardParticipantPage(
        expoId: String,
        cursor: Long?,
    ): DetailPage<StandardParticipantDetail> = http.get().uri(detailPage("standard-participants", expoId, cursor)).fetchRequired(NAME)

    fun traineePage(
        expoId: String,
        cursor: Long?,
    ): DetailPage<TraineeDetailItem> = http.get().uri(detailPage("trainees", expoId, cursor)).fetchRequired(NAME)

    /** 없으면 null */
    fun trainee(traineeId: Long): TraineeDetail? = http.get().uri("/internal/trainees/{traineeId}/details", traineeId).fetchOrNull(NAME)

    /** 요청 순서대로 모두 돌려준다. 없는 ID나 다른 행사 참가자가 섞이면 공급자가 404로 거절하고, 여기서는 실패다. */
    fun standardParticipantBriefs(
        expoId: String,
        participantIds: List<Long>,
    ): List<StandardParticipantBrief> {
        require(participantIds.size <= MAX_BRIEF_IDS)
        return http
            .post()
            .uri("/internal/standard-participants/details")
            .body(mapOf("expoId" to expoId, "participantIds" to participantIds))
            .fetchRequired(NAME)
    }

    // cursor가 null이면 쿼리에서 빠진다(처음부터)
    private fun detailPage(
        kind: String,
        expoId: String,
        cursor: Long?,
    ): (UriBuilder) -> URI =
        {
            it
                .path("/internal/expos/{expoId}/$kind/details")
                .queryParam("size", PAGE_SIZE)
                .queryParamIfPresent("cursor", Optional.ofNullable(cursor))
                .build(expoId)
        }

    companion object {
        const val NAME = "User"
        const val PAGE_SIZE = 500

        // ponytail: User GetStandardParticipantNamesReqDto.MAX_PARTICIPANT_IDS(10,000)와 같다. 공급자 상한이 바뀌면 함께 바꾼다.
        const val MAX_BRIEF_IDS = 10_000
    }
}

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
    val personalInformationStatus: Boolean?,
    val applicationType: ApplicationType,
    val information: InformationResDto,
    // 설문에 답하지 않았으면 null
    val surveyAnswer: InformationResDto?,
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
