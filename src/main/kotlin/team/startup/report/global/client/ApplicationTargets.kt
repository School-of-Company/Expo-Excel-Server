package team.startup.report.global.client

import com.fasterxml.jackson.annotation.JsonAnySetter
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

enum class SessionType { STANDARD, TRAINING }

enum class ApplicationStatus { CONFIRMED, WAITING, CANCELLED }

enum class ApplicationSort { CREATED_ASC, CREATED_DESC }

data class ApplicationFilter(
    val programType: SessionType? = null,
    val programId: Long? = null,
    val groupId: UUID? = null,
    val applicationId: UUID? = null,
    val status: ApplicationStatus? = ApplicationStatus.CONFIRMED,
    val search: String? = null,
    val from: Instant? = null,
    val until: Instant? = null,
    val sort: ApplicationSort = ApplicationSort.CREATED_DESC,
) {
    @JsonAnySetter
    fun rejectUnknown(
        name: String,
        value: JsonNode,
    ): Nothing = throw IllegalArgumentException("Unsupported field: $name")
}

data class ApplicationTarget(
    val expoId: UUID,
    val application: ApplicationRow,
    val registrationSource: RegistrationSource,
)

data class RegistrationSource(
    val expoId: UUID,
    val participantId: Long,
)

data class ApplicationRow(
    val applicationId: UUID,
    val participantId: Long,
    val traineeId: Long?,
    val programType: SessionType,
    val programId: Long,
    val groupId: UUID,
    val status: ApplicationStatus,
    val createdAt: Instant,
    val formId: UUID,
    val formVersion: Long,
    val definition: JsonNode,
    val questions: JsonNode,
    val answers: JsonNode,
)

data class ApplicationSelection(
    val items: List<ApplicationTarget>,
    val totalElements: Int,
)

data class ApplicationSnapshot(
    val snapshotId: UUID,
    val snapshotAt: Instant,
    val expiresAt: Instant,
    val totalElements: Int,
    val items: List<ApplicationTarget>,
    val nextCursor: String?,
)
