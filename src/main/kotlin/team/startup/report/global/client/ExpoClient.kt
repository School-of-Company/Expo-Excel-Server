package team.startup.report.global.client

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import team.startup.report.domain.excel.service.TrainingCategory
import team.startup.report.global.exception.ExpectedException
import tools.jackson.databind.JsonNode

/** Expo-Expo-Server 내부 조회(PR #50). */
class ExpoClient(
    private val http: RestClient,
) {
    fun requireAdminAccess(
        expoId: String,
        adminId: Long,
        authorization: String,
    ) {
        try {
            val body =
                http
                    .get()
                    .uri("/internal/expo/{expoId}/admin-access", expoId)
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .retrieve()
                    .body(JsonNode::class.java)
                    ?: error("권한 응답 없음")
            check(body.path("adminId").isIntegralNumber && body.path("adminId").canConvertToLong())
            check(body.path("adminId").asLong() == adminId && adminId > 0)
            check(body.path("expoId").isString && body.path("expoId").asString() == expoId)
            check(body.path("permission").asString() == "APPLICATION_READ")
            check(body.path("policyVersion").asString() == "GLOBAL_ADMIN_APPLICATION_READ_V1")
            check(body.path("scope").asString() == "GLOBAL_ADMIN")
        } catch (e: RestClientResponseException) {
            val status =
                when (e.statusCode.value()) {
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
    fun expo(expoId: String): ExpoSummary? = http.get().uri("/internal/expo/{expoId}", expoId).fetchOrNull(NAME)

    /** 행사가 없거나 프로그램이 그 행사 소속이 아니면 null(공급자는 둘 다 404) */
    fun standardProgram(
        expoId: String,
        programId: Long,
    ): StandardProgramSummary? =
        http.get().uri("/internal/expo/{expoId}/standard-programs/{programId}", expoId, programId).fetchOrNull(NAME)

    /** 하나라도 없거나 다른 행사 소속이면 공급자가 404로 거절하고, 여기서는 실패다. */
    fun trainingPrograms(
        expoId: String,
        programIds: List<Long>,
    ): List<TrainingProgramSummary> {
        require(programIds.size <= MAX_PROGRAM_IDS)
        return http
            .post()
            .uri("/internal/expo/{expoId}/training-programs/batch", expoId)
            .body(mapOf("programIds" to programIds))
            .fetchRequired(NAME)
    }

    companion object {
        const val NAME = "Expo"
        const val MAX_PROGRAM_IDS = 100
    }
}

data class ExpoSummary(
    val title: String?,
    // Expo 내부 조회(GET /internal/expo/{expoId})가 아직 내려주지 않아 현재는 항상 null. 계약에 추가되면 그대로 쓴다.
    val location: String? = null,
)

data class StandardProgramSummary(
    val id: Long,
)

data class TrainingProgramSummary(
    val id: Long,
    val title: String?,
    val startedAt: String,
    val endedAt: String,
    val category: TrainingCategory?,
)
