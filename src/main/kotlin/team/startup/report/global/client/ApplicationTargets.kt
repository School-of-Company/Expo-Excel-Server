package team.startup.report.global.client

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class SessionType { STANDARD, TRAINING }

enum class ApplicationStatus { CONFIRMED, WAITING, CANCELLED }

// Application AdminApplicationSort와 같다. PROGRAM은 신청 당시 회차 제목, SESSION은 신청 당시 운영 시작 순이다
enum class ApplicationSort { CREATED_ASC, CREATED_DESC, RECEIPT_ASC, RECEIPT_DESC, PROGRAM_ASC, PROGRAM_DESC, SESSION_ASC, SESSION_DESC }

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
    @field:Schema(
        description = "접수 상태 배열(단일 값도 허용). 생략하면 CONFIRMED, null이면 모든 상태. 출석용 양식은 확정 신청만 허용",
        example = "[\"CONFIRMED\",\"CANCELLED\"]",
    )
    @field:JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    val status: List<ApplicationStatus>? = listOf(ApplicationStatus.CONFIRMED),
    @field:Schema(description = "회차 제목 검색어(1–200자)", example = "예시 강연")
    val search: String? = null,
    @field:Schema(description = "신청 생성 시각 시작(포함)", example = "2026-01-01T00:00:00Z")
    val from: Instant? = null,
    @field:Schema(description = "신청 생성 시각 종료(제외). 시작보다 뒤여야 합니다", example = "2026-02-01T00:00:00Z")
    val until: Instant? = null,
    @field:Schema(description = "Application 정렬. 신청 원장만 이 순서로 출력하고 사람 명단은 기존 ID 순서로 출력", example = "CREATED_DESC")
    val sort: ApplicationSort = ApplicationSort.CREATED_DESC,
    // 아래 셋은 Application 관리자 신청 목록과 같은 조건이다. 값이 없으면 보내지 않는다
    @field:Schema(description = "참가자 이름·전화번호 부분 일치(1–50자)", example = "홍길동")
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val q: String? = null,
    @field:Schema(description = "참가자 직업 코드", example = "TEACHER")
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val occupation: String? = null,
    @field:Schema(description = "신청 당시 회차 운영 시작일(KST)", example = "2026-10-31")
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val eventDate: LocalDate? = null,
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
    // 행사별 접수 순번. 이력은 created_at·id 순 상태 기록이다. 구버전 공급자 응답이면 null이고 신청 원장은 만들지 않는다
    val receiptNumber: Long? = null,
    val history: List<ApplicationHistory>? = null,
)

data class ApplicationHistory(
    val id: Long,
    val status: ApplicationStatus,
    val createdAt: Instant,
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
