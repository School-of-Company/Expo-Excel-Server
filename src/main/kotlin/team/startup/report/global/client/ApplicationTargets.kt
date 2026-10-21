package team.startup.report.global.client

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.swagger.v3.oas.annotations.media.Schema
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

enum class SessionType { STANDARD, TRAINING }

enum class ApplicationStatus { CONFIRMED, WAITING, CANCELLED }

enum class ApplicationSort { CREATED_ASC, CREATED_DESC }

@Schema(description = "관리자 신청 필터. 검색은 회차 제목, 날짜는 신청 생성 시각입니다.")
data class ApplicationFilter(
    @field:Schema(description = "일반/연수 회차 유형", example = "STANDARD")
    val programType: SessionType? = null,
    @field:Schema(description = "양수 회차 ID. 유형을 함께 지정해야 합니다", example = "7")
    val programId: Long? = null,
    @field:Schema(description = "프로그램 묶음 UUID", example = "33333333-3333-3333-3333-333333333333")
    val groupId: UUID? = null,
    @field:Schema(description = "신청 UUID", example = "22222222-2222-2222-2222-222222222222")
    val applicationId: UUID? = null,
    @field:Schema(description = "접수 상태. 생략하면 CONFIRMED, null이면 모든 상태. 출석용 양식은 확정 신청만 허용", example = "CONFIRMED")
    val status: ApplicationStatus? = ApplicationStatus.CONFIRMED,
    @field:Schema(description = "회차 제목 검색어(1–200자)", example = "예시 강연")
    val search: String? = null,
    @field:Schema(description = "신청 생성 시각 시작(포함)", example = "2026-01-01T00:00:00Z")
    val from: Instant? = null,
    @field:Schema(description = "신청 생성 시각 종료(제외). 시작보다 뒤여야 합니다", example = "2026-02-01T00:00:00Z")
    val until: Instant? = null,
    @field:Schema(description = "신청 생성 시각과 UUID 정렬. 사람 명단은 기존 ID 순서로 출력", example = "CREATED_DESC")
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
    // PRE_REGISTRATION·ONSITE. 사전/현장 정원이 없는 회차와 V23 이전 신청은 null이다
    val admissionRoute: String? = null,
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
