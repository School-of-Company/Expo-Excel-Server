package team.startup.report.global.client.expo

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import team.startup.report.domain.excel.service.TrainingCategory
import team.startup.report.global.client.ClientResilience
import team.startup.report.global.client.ProviderStatusException
import team.startup.report.global.client.fetchOrNull
import team.startup.report.global.client.fetchRequired
import team.startup.report.global.exception.ExpectedException
import tools.jackson.databind.JsonNode

/** Expo-Expo-Server 내부 조회(PR #50). */
class ExpoClient(
    private val api: ExpoApi,
    private val resilience: ClientResilience = ClientResilience(NAME),
) {
    fun requireAdminAccess(
        expoId: String,
        adminId: Long,
        authorization: String,
    ) {
        try {
            val body =
                resilience.read.run { api.adminAccess(expoId, authorization) }
                    ?: error("권한 응답 없음")
            check(body.path("adminId").isIntegralNumber && body.path("adminId").canConvertToLong())
            check(body.path("adminId").asLong() == adminId && adminId > 0)
            check(body.path("expoId").isString && body.path("expoId").asString() == expoId)
            check(body.path("permission").asString() == "APPLICATION_READ")
            check(body.path("policyVersion").asString() == "GLOBAL_ADMIN_APPLICATION_READ_V1")
            check(body.path("scope").asString() == "GLOBAL_ADMIN")
        } catch (e: ProviderStatusException) {
            val status =
                when (e.status) {
                    401 -> HttpStatus.UNAUTHORIZED
                    403 -> HttpStatus.FORBIDDEN
                    404 -> HttpStatus.NOT_FOUND
                    else -> HttpStatus.BAD_GATEWAY
                }
            throw ExpectedException(status, "행사 조회 권한을 확인할 수 없습니다.")
        } catch (_: Exception) {
            throw ExpectedException(HttpStatus.BAD_GATEWAY, "행사 조회 권한을 확인할 수 없습니다.")
        }
    }

    /** 없으면 null */
    fun expo(expoId: String): ExpoSummary? = resilience.read.fetchOrNull { api.expo(expoId) }

    /** 행사가 없거나 프로그램이 그 행사 소속이 아니면 null(공급자는 둘 다 404) */
    fun standardProgram(
        expoId: String,
        programId: Long,
    ): StandardProgramSummary? = resilience.read.fetchOrNull { api.standardProgram(expoId, programId) }

    /** 없거나 다른 행사 소속이면 null(공급자 404) */
    fun trainingProgram(
        expoId: String,
        programId: Long,
    ): TrainingProgramSummary? =
        resilience.write
            .fetchOrNull { api.trainingPrograms(expoId, mapOf("programIds" to listOf(programId))) }
            ?.single()
            ?.also { check(it.id == programId) { "$NAME 서비스 대상 오류" } }

    /** 하나라도 없거나 다른 행사 소속이면 공급자가 404로 거절하고, 여기서는 실패다. */
    fun trainingPrograms(
        expoId: String,
        programIds: List<Long>,
    ): List<TrainingProgramSummary> {
        require(programIds.size <= MAX_PROGRAM_IDS)
        return resilience.write.fetchRequired { api.trainingPrograms(expoId, mapOf("programIds" to programIds)) }
    }

    companion object {
        const val NAME = "Expo"
        const val MAX_PROGRAM_IDS = 100
    }
}

interface ExpoApi {
    // 요청자의 Authorization을 그대로 넘긴다
    @GetMapping("/internal/expo/{expoId}/admin-access")
    fun adminAccess(
        @PathVariable("expoId") expoId: String,
        @RequestHeader(HttpHeaders.AUTHORIZATION) authorization: String,
    ): JsonNode?

    @GetMapping("/internal/expo/{expoId}")
    fun expo(
        @PathVariable("expoId") expoId: String,
    ): ExpoSummary?

    @GetMapping("/internal/expo/{expoId}/standard-programs/{programId}")
    fun standardProgram(
        @PathVariable("expoId") expoId: String,
        @PathVariable("programId") programId: Long,
    ): StandardProgramSummary?

    @PostMapping("/internal/expo/{expoId}/training-programs/batch", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun trainingPrograms(
        @PathVariable("expoId") expoId: String,
        @RequestBody body: Map<String, List<Long>>,
    ): List<TrainingProgramSummary>?
}

data class ExpoSummary(
    val title: String?,
    // Expo 내부 조회(GET /internal/expo/{expoId})가 아직 내려주지 않아 현재는 항상 null. 계약에 추가되면 그대로 쓴다.
    val location: String? = null,
)

data class StandardProgramSummary(
    val id: Long,
    val title: String? = null,
    // ISO 지역 일시 또는 UTC Instant("...Z", Expo#196 이후). parseProgramDateTime으로 KST로 읽는다. 일정이 없는 회차는 null
    val startedAt: String? = null,
    val endedAt: String? = null,
)

data class TrainingProgramSummary(
    val id: Long,
    val title: String?,
    val startedAt: String,
    val endedAt: String,
    val category: TrainingCategory?,
)
