package team.startup.report.global.client.application

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import team.startup.report.global.client.ClientResilience
import team.startup.report.global.client.ProviderStatusException
import team.startup.report.global.client.fetchRequired
import team.startup.report.global.exception.ExpectedException
import java.time.LocalDate
import java.util.UUID

/** Expo-Application-Server 내부 조회(PR #23). 공급자는 ID 오름차순으로 주지만 호출자가 applicationId로 다시 정렬한다. */
class ApplicationClient(
    private val api: ApplicationApi,
    private val resilience: ClientResilience = ClientResilience(NAME),
) {
    fun fieldVisits(
        expoId: UUID,
        visitDate: LocalDate?,
        cursor: UUID?,
    ): FieldVisitPage = resilience.read.fetchRequired { api.fieldVisits(expoId.toString(), visitDate?.toString(), cursor?.toString(), 100) }

    fun selectedTargets(
        expoId: UUID,
        applicationIds: List<UUID>,
    ): ApplicationSelection =
        resilience.write.targetResponse {
            api.selectedTargets(
                expoId.toString(),
                mapOf("applicationIds" to applicationIds),
            )
        }

    fun createTargetSnapshot(
        expoId: UUID,
        filter: ApplicationFilter,
    ): ApplicationSnapshot =
        resilience.write.targetResponse(snapshotCreation = true) {
            api.createTargetSnapshot(expoId.toString(), mapOf("filter" to filter, "limit" to 500))
        }

    fun nextTargetSnapshot(
        expoId: UUID,
        cursor: String,
    ): ApplicationSnapshot = resilience.read.targetResponse { api.nextTargetSnapshot(expoId.toString(), cursor) }

    fun selectedPreregistrations(
        expoId: UUID,
        keys: List<String>,
    ): PreregistrationSelection = resilience.write.targetResponse { api.selectedPreregistrations(expoId.toString(), mapOf("keys" to keys)) }

    fun createPreregistrationSnapshot(
        expoId: UUID,
        filter: RegistrationFilter,
    ): PreregistrationSnapshot =
        resilience.write.targetResponse(snapshotCreation = true) {
            api.createPreregistrationSnapshot(expoId.toString(), mapOf("filter" to filter, "limit" to 500))
        }

    fun nextPreregistrationSnapshot(
        expoId: UUID,
        cursor: String,
    ): PreregistrationSnapshot = resilience.read.targetResponse { api.nextPreregistrationSnapshot(expoId.toString(), cursor) }

    private fun <T : Any> ClientResilience.Guard.targetResponse(
        snapshotCreation: Boolean = false,
        call: () -> T?,
    ): T =
        try {
            run(call) ?: error("Application 대상 응답 없음")
        } catch (e: ProviderStatusException) {
            val status =
                when (e.status) {
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
        resilience.read.fetchRequired { api.standardApplications(programId) }

    fun trainingApplications(traineeIds: List<Long>): List<TrainingApplication> {
        require(traineeIds.size <= MAX_TRAINEE_IDS)
        return resilience.write.fetchRequired { api.trainingApplications(mapOf("traineeIds" to traineeIds)) }
    }

    companion object {
        const val NAME = "Application"
        const val MAX_TRAINEE_IDS = 500
    }
}

private const val TARGET_PATH = "/internal/expos/{expoId}/program-session-applications/report-targets"
private const val PREREGISTRATION_PATH = "/internal/expos/{expoId}/preregistrations/report-targets"

/** null인 쿼리 값(visitDate·cursor)은 쿼리에서 빠진다. 날짜는 ISO(yyyy-MM-dd) 문자열로 넘긴다. */
interface ApplicationApi {
    @GetMapping("/internal/expos/{expoId}/field-visits")
    fun fieldVisits(
        @PathVariable("expoId") expoId: String,
        @RequestParam("visitDate", required = false) visitDate: String?,
        @RequestParam("cursor", required = false) cursor: String?,
        @RequestParam("size") size: Int,
    ): FieldVisitPage?

    @PostMapping(TARGET_PATH + "/selection", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun selectedTargets(
        @PathVariable("expoId") expoId: String,
        @RequestBody body: Map<String, Any>,
    ): ApplicationSelection?

    @PostMapping(TARGET_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createTargetSnapshot(
        @PathVariable("expoId") expoId: String,
        @RequestBody body: Map<String, Any>,
    ): ApplicationSnapshot?

    @GetMapping(TARGET_PATH)
    fun nextTargetSnapshot(
        @PathVariable("expoId") expoId: String,
        @RequestParam("cursor") cursor: String,
    ): ApplicationSnapshot?

    @PostMapping(PREREGISTRATION_PATH + "/selection", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun selectedPreregistrations(
        @PathVariable("expoId") expoId: String,
        @RequestBody body: Map<String, Any>,
    ): PreregistrationSelection?

    @PostMapping(PREREGISTRATION_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createPreregistrationSnapshot(
        @PathVariable("expoId") expoId: String,
        @RequestBody body: Map<String, Any>,
    ): PreregistrationSnapshot?

    @GetMapping(PREREGISTRATION_PATH)
    fun nextPreregistrationSnapshot(
        @PathVariable("expoId") expoId: String,
        @RequestParam("cursor") cursor: String,
    ): PreregistrationSnapshot?

    @GetMapping("/internal/standard-program-applications/program/{programId}")
    fun standardApplications(
        @PathVariable("programId") programId: Long,
    ): List<StandardApplication>?

    @PostMapping("/internal/training-program-applications/trainees", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun trainingApplications(
        @RequestBody body: Map<String, Any>,
    ): List<TrainingApplication>?
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
