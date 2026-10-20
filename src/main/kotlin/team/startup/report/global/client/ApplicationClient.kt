package team.startup.report.global.client

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonUnwrapped
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import team.startup.report.global.exception.ExpectedException
import java.util.UUID

/** Expo-Application-Server 내부 조회(PR #23). 공급자는 ID 오름차순으로 주지만 호출자가 applicationId로 다시 정렬한다. */
class ApplicationClient(
    private val http: RestClient,
) {
    fun selectedTargets(
        expoId: UUID,
        applicationIds: List<UUID>,
    ): ApplicationSelection =
        http
            .post()
            .uri(TARGET_PATH + "/selection", expoId)
            .body(mapOf("applicationIds" to applicationIds))
            .targetResponse()

    /** [q]는 Application이 User 이름·전화번호 부분 일치로 참가자 후보를 좁히는 내부 조건이다. 관리자 필터로는 받지 않는다. */
    fun createTargetSnapshot(
        expoId: UUID,
        filter: ApplicationFilter,
        q: String? = null,
    ): ApplicationSnapshot =
        http
            .post()
            .uri(TARGET_PATH, expoId)
            .body(mapOf("filter" to SnapshotFilter(filter, q), "limit" to 500))
            .targetResponse(snapshotCreation = true)

    fun nextTargetSnapshot(
        expoId: UUID,
        cursor: String,
    ): ApplicationSnapshot = http.get().uri { it.path(TARGET_PATH).queryParam("cursor", cursor).build(expoId) }.targetResponse()

    private inline fun <reified T : Any> RestClient.RequestHeadersSpec<*>.targetResponse(snapshotCreation: Boolean = false): T =
        try {
            retrieve().body(object : ParameterizedTypeReference<T>() {}) ?: error("Application 대상 응답 없음")
        } catch (e: RestClientResponseException) {
            val status =
                when (e.statusCode.value()) {
                    400 -> HttpStatus.BAD_REQUEST

                    404 -> HttpStatus.NOT_FOUND

                    410 -> HttpStatus.GONE

                    413 -> HttpStatus.PAYLOAD_TOO_LARGE

                    // Application 스냅샷 생성의 10,000건 상한은 422이며 Report 계약은 413이다.
                    422 -> if (snapshotCreation) HttpStatus.PAYLOAD_TOO_LARGE else HttpStatus.BAD_GATEWAY

                    else -> HttpStatus.BAD_GATEWAY
                }
            throw ExpectedException(status, "신청 대상 조회에 실패했습니다. 범위와 스냅샷 유효기간을 확인해 주세요.")
        }

    /** 프로그램의 일반 신청. 공급자는 프로그램 존재를 확인하지 않으므로 존재 확인은 Expo로 한다. */
    fun standardApplications(programId: Long): List<StandardApplication> =
        http.get().uri("/internal/standard-program-applications/program/{programId}", programId).fetchRequired(NAME)

    fun trainingApplications(traineeIds: List<Long>): List<TrainingApplication> {
        require(traineeIds.size <= MAX_TRAINEE_IDS)
        return http
            .post()
            .uri("/internal/training-program-applications/trainees")
            .body(mapOf("traineeIds" to traineeIds))
            .fetchRequired(NAME)
    }

    companion object {
        const val NAME = "Application"
        const val MAX_TRAINEE_IDS = 500
        private const val TARGET_PATH = "/internal/expos/{expoId}/program-session-applications/report-targets"
    }
}

private data class SnapshotFilter(
    @get:JsonUnwrapped val filter: ApplicationFilter,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val q: String?,
)

data class StandardApplication(
    val applicationId: Long,
    val participantId: Long,
)

data class TrainingApplication(
    val applicationId: Long,
    val traineeId: Long,
    val trainingProgramId: Long,
)
