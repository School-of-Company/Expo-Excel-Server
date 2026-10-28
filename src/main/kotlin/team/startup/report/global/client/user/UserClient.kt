package team.startup.report.global.client.user

import org.springframework.web.client.RestClient
import org.springframework.web.util.UriBuilder
import team.startup.report.global.client.ClientResilience
import team.startup.report.global.client.fetchOrNull
import team.startup.report.global.client.fetchRequired
import java.net.URI
import java.util.Optional

/**
 * Expo-User-Server 내부 조회(PR #47·#49). 상세 목록은 ID 오름차순 커서 페이지(최대 500)이고, 일괄 조회는 요청 ID를 모두 돌려주거나 404다.
 * 전화번호는 응답 본문으로만 오가고 URL에 넣지 않는다.
 */
class UserClient(
    private val http: RestClient,
    private val resilience: ClientResilience = ClientResilience(NAME),
) {
    fun standardParticipantPage(
        expoId: String,
        cursor: Long?,
    ): DetailPage<StandardParticipantDetail> =
        http.get().uri(detailPage("standard-participants", expoId, cursor)).fetchRequired(resilience.read)

    fun traineePage(
        expoId: String,
        cursor: Long?,
    ): DetailPage<TraineeDetailItem> = http.get().uri(detailPage("trainees", expoId, cursor)).fetchRequired(resilience.read)

    /** 없으면 null */
    fun trainee(traineeId: Long): TraineeDetail? =
        http.get().uri("/internal/trainees/{traineeId}/details", traineeId).fetchOrNull(resilience.read)

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
            .fetchRequired(resilience.write)
    }

    /**
     * 일반 참가자 이름. 요청한 ID를 모두 돌려주거나, 없는 ID·다른 행사 참가자가 하나라도 있으면 공급자가 404로 거절하고 여기서는 null이다.
     * 한 번에 [MAX_NAME_IDS]개까지다.
     */
    fun standardParticipantNames(
        expoId: String,
        participantIds: List<Long>,
    ): List<StandardParticipantName>? {
        require(participantIds.size in 1..MAX_NAME_IDS)
        return http
            .post()
            .uri("/internal/standard-participants/names")
            .body(mapOf("expoId" to expoId, "participantIds" to participantIds))
            .fetchOrNull(resilience.write)
    }

    /** 연수자 이름. 거절 규칙과 상한은 [standardParticipantNames]와 같다. */
    fun traineeNames(
        expoId: String,
        traineeIds: List<Long>,
    ): List<TraineeName>? {
        require(traineeIds.size in 1..MAX_NAME_IDS)
        return http
            .post()
            .uri("/internal/trainees/names")
            .body(mapOf("expoId" to expoId, "traineeIds" to traineeIds))
            .fetchOrNull(resilience.write)
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

        // User GetStandardParticipantNamesReqDto.MAX_PARTICIPANT_IDS·GetTraineeNamesReqDto.MAX_TRAINEE_IDS(각 10,000)와 같다.
        const val MAX_NAME_IDS = 10_000
    }
}
