package team.startup.report.global.client.user

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import team.startup.report.global.client.ClientResilience
import team.startup.report.global.client.fetchOrNull
import team.startup.report.global.client.fetchRequired

/**
 * Expo-User-Server 내부 조회(PR #47·#49). 상세 목록은 ID 오름차순 커서 페이지(최대 500)이고, 일괄 조회는 요청 ID를 모두 돌려주거나 404다.
 * 전화번호는 응답 본문으로만 오가고 URL에 넣지 않는다.
 */
class UserClient(
    private val api: UserApi,
    private val resilience: ClientResilience = ClientResilience(NAME),
) {
    fun standardParticipantPage(
        expoId: String,
        cursor: Long?,
    ): DetailPage<StandardParticipantDetail> = resilience.read.fetchRequired { api.standardParticipantPage(expoId, PAGE_SIZE, cursor) }

    fun traineePage(
        expoId: String,
        cursor: Long?,
    ): DetailPage<TraineeDetailItem> = resilience.read.fetchRequired { api.traineePage(expoId, PAGE_SIZE, cursor) }

    /** 없으면 null */
    fun trainee(traineeId: Long): TraineeDetail? = resilience.read.fetchOrNull { api.trainee(traineeId) }

    /** 요청 순서대로 모두 돌려준다. 없는 ID나 다른 행사 참가자가 섞이면 공급자가 404로 거절하고, 여기서는 실패다. */
    fun standardParticipantBriefs(
        expoId: String,
        participantIds: List<Long>,
    ): List<StandardParticipantBrief> {
        require(participantIds.size <= MAX_BRIEF_IDS)
        return resilience.write.fetchRequired {
            api.standardParticipantBriefs(mapOf("expoId" to expoId, "participantIds" to participantIds))
        }
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
        return resilience.write.fetchOrNull {
            api.standardParticipantNames(mapOf("expoId" to expoId, "participantIds" to participantIds))
        }
    }

    /** 연수자 이름. 거절 규칙과 상한은 [standardParticipantNames]와 같다. */
    fun traineeNames(
        expoId: String,
        traineeIds: List<Long>,
    ): List<TraineeName>? {
        require(traineeIds.size in 1..MAX_NAME_IDS)
        return resilience.write.fetchOrNull { api.traineeNames(mapOf("expoId" to expoId, "traineeIds" to traineeIds)) }
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

/** 상세 목록의 cursor가 null이면 쿼리에서 빠진다(처음부터). */
interface UserApi {
    @GetMapping("/internal/expos/{expoId}/standard-participants/details")
    fun standardParticipantPage(
        @PathVariable("expoId") expoId: String,
        @RequestParam("size") size: Int,
        @RequestParam("cursor", required = false) cursor: Long?,
    ): DetailPage<StandardParticipantDetail>?

    @GetMapping("/internal/expos/{expoId}/trainees/details")
    fun traineePage(
        @PathVariable("expoId") expoId: String,
        @RequestParam("size") size: Int,
        @RequestParam("cursor", required = false) cursor: Long?,
    ): DetailPage<TraineeDetailItem>?

    @GetMapping("/internal/trainees/{traineeId}/details")
    fun trainee(
        @PathVariable("traineeId") traineeId: Long,
    ): TraineeDetail?

    @PostMapping("/internal/standard-participants/details", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun standardParticipantBriefs(
        @RequestBody body: Map<String, Any>,
    ): List<StandardParticipantBrief>?

    @PostMapping("/internal/standard-participants/names", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun standardParticipantNames(
        @RequestBody body: Map<String, Any>,
    ): List<StandardParticipantName>?

    @PostMapping("/internal/trainees/names", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun traineeNames(
        @RequestBody body: Map<String, Any>,
    ): List<TraineeName>?
}
