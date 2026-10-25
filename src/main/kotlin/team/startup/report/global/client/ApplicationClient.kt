package team.startup.report.global.client

import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import team.startup.report.global.exception.ExpectedException
import java.time.LocalDate
import java.util.UUID

/** Expo-Application-Server 내부 조회(PR #23). 공급자는 ID 오름차순으로 주지만 호출자가 applicationId로 다시 정렬한다. */
class ApplicationClient(
    private val http: RestClient,
    private val resilience: ClientResilience = ClientResilience(NAME),
) {
    fun fieldVisits(
        expoId: UUID,
        visitDate: LocalDate?,
        cursor: UUID?,
    ): FieldVisitPage =
        http
            .get()
            .uri {
                it
                    .path("/internal/expos/{expoId}/field-visits")
                    .apply {
                        if (visitDate != null) queryParam("visitDate", visitDate)
                        if (cursor != null) queryParam("cursor", cursor)
                    }.queryParam("size", 100)
                    .build(expoId)
            }.fetchRequired(resilience.read)

    fun selectedTargets(
        expoId: UUID,
        applicationIds: List<UUID>,
    ): ApplicationSelection =
        http
            .post()
            .uri(TARGET_PATH + "/selection", expoId)
            .body(mapOf("applicationIds" to applicationIds))
            .targetResponse(resilience.write)

    fun createTargetSnapshot(
        expoId: UUID,
        filter: ApplicationFilter,
    ): ApplicationSnapshot =
        http
            .post()
            .uri(TARGET_PATH, expoId)
            .body(mapOf("filter" to filter, "limit" to 500))
            .targetResponse(resilience.write, snapshotCreation = true)

    fun nextTargetSnapshot(
        expoId: UUID,
        cursor: String,
    ): ApplicationSnapshot =
        http.get().uri { it.path(TARGET_PATH).queryParam("cursor", cursor).build(expoId) }.targetResponse(resilience.read)

    fun selectedPreregistrations(
        expoId: UUID,
        keys: List<String>,
    ): PreregistrationSelection =
        http
            .post()
            .uri(PREREGISTRATION_PATH + "/selection", expoId)
            .body(mapOf("keys" to keys))
            .targetResponse(resilience.write)

    fun createPreregistrationSnapshot(
        expoId: UUID,
        filter: RegistrationFilter,
    ): PreregistrationSnapshot =
        http
            .post()
            .uri(PREREGISTRATION_PATH, expoId)
            .body(mapOf("filter" to filter, "limit" to 500))
            .targetResponse(resilience.write, snapshotCreation = true)

    fun nextPreregistrationSnapshot(
        expoId: UUID,
        cursor: String,
    ): PreregistrationSnapshot =
        http.get().uri { it.path(PREREGISTRATION_PATH).queryParam("cursor", cursor).build(expoId) }.targetResponse(resilience.read)

    private inline fun <reified T : Any> RestClient.RequestHeadersSpec<*>.targetResponse(
        guard: ClientResilience.Guard,
        snapshotCreation: Boolean = false,
    ): T =
        try {
            guard.run { retrieve().body(object : ParameterizedTypeReference<T>() {}) } ?: error("Application 대상 응답 없음")
        } catch (e: RestClientResponseException) {
            val status =
                when (e.statusCode.value()) {
                    400 -> HttpStatus.BAD_REQUEST

                    404 -> HttpStatus.NOT_FOUND

                    410 -> HttpStatus.GONE

                    413 -> HttpStatus.PAYLOAD_TOO_LARGE

                    // 스냅샷 생성 상한(프로그램 10,000건, 사전등록 10,000행)은 422이며 Report 계약은 413이다.
                    422 -> if (snapshotCreation) HttpStatus.PAYLOAD_TOO_LARGE else HttpStatus.BAD_GATEWAY

                    else -> HttpStatus.BAD_GATEWAY
                }
            throw ExpectedException(status, "신청 대상 조회에 실패했습니다. 범위와 스냅샷 유효기간을 확인해 주세요.")
        }

    /** 프로그램의 일반 신청. 공급자는 프로그램 존재를 확인하지 않으므로 존재 확인은 Expo로 한다. */
    fun standardApplications(programId: Long): List<StandardApplication> =
        http.get().uri("/internal/standard-program-applications/program/{programId}", programId).fetchRequired(resilience.read)

    fun trainingApplications(traineeIds: List<Long>): List<TrainingApplication> {
        require(traineeIds.size <= MAX_TRAINEE_IDS)
        return http
            .post()
            .uri("/internal/training-program-applications/trainees")
            .body(mapOf("traineeIds" to traineeIds))
            .fetchRequired(resilience.write)
    }

    companion object {
        const val NAME = "Application"
        const val MAX_TRAINEE_IDS = 500
        private const val TARGET_PATH = "/internal/expos/{expoId}/program-session-applications/report-targets"
        private const val PREREGISTRATION_PATH = "/internal/expos/{expoId}/preregistrations/report-targets"
    }
}

data class StandardApplication(
    val applicationId: Long,
    val participantId: Long,
)

data class TrainingApplication(
    val applicationId: Long,
    val traineeId: Long,
    val trainingProgramId: Long,
)
