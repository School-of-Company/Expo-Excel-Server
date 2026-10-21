package team.startup.report.global.client

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

// Expo-Application-Server #284 사전등록 report-targets(ReportPreregistrationTargetDto). 필드 이름·의미는 공급자와 같다.

enum class PreregistrationStatus { CONFIRMED, WAITING, PENDING, CANCELLED }

enum class PreregistrationRowType { APPLICATION, RESERVATION }

@Schema(description = "사전등록 접수대장 필터와 같은 의미. 페이지 없음")
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RegistrationFilter(
    @field:Schema(description = "양수 회차 ID", example = "3")
    val sessionId: Long? = null,
    @field:Schema(description = "상태 배열(단일 값도 허용). 생략하면 모든 상태", example = "[\"CONFIRMED\",\"CANCELLED\"]")
    @field:JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    val status: List<PreregistrationStatus>? = null,
    @field:Schema(description = "이름·전화번호 부분 일치(1–50자). 확정·취소 행은 User 현재 값, 예약 행은 제출 당시 대표 값", example = "홍길동")
    val q: String? = null,
) {
    @JsonAnySetter
    fun rejectUnknown(
        name: String,
        value: JsonNode,
    ): Nothing = throw IllegalArgumentException("Unsupported field: $name")
}

/** APPLICATION은 User 참가자×회차, RESERVATION은 User 미저장 예약 일행×회차다. reservationId는 참가자 ID가 아니다. */
data class PreregistrationTarget(
    val expoId: UUID,
    val key: String,
    val rowType: PreregistrationRowType,
    val status: PreregistrationStatus,
    val applicationId: Long?,
    val reservationId: String?,
    val participantId: Long?,
    val representativeId: Long?,
    val representative: Boolean?,
    val reservationKind: String?,
    val applicants: Int,
    val seatCategory: String?,
    val seatCounts: SeatCounts?,
    val session: PreregistrationSession,
    val createdAt: Instant?,
    val cancelledAt: Instant?,
    val cancellationActorType: String?,
    val cancellationActorId: Long?,
    val history: List<PreregistrationTransition>,
    val promotedFromWaiting: Boolean?,
    // 신청 당시 일행 제출본 {name, phoneNumber, occupation, informationJson, questions}. 동행자 행도 대표 제출본이고 과거 행은 null
    val registrationSnapshot: JsonNode?,
    // 조회 시점 User 현재 프로필. Report는 참가자 ID 대조에만 쓰고 값은 User 상세 목록에서 읽는다
    val participant: JsonNode? = null,
)

/** 회차의 현재 저장 정의다(제출 당시 정의가 아니다). */
data class PreregistrationSession(
    val sessionId: Long,
    val title: String?,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val place: String?,
    val deleted: Boolean,
)

data class SeatCounts(
    val teacher: Int,
    val other: Int,
    val unrecorded: Int,
)

data class PreregistrationTransition(
    val status: String,
    val createdAt: Instant?,
    val actorType: String,
    val actorParticipantId: Long?,
    val actorId: Long?,
)

data class PreregistrationSelection(
    val items: List<PreregistrationTarget>,
    val totalElements: Int,
)

data class PreregistrationSnapshot(
    val snapshotId: UUID,
    val snapshotAt: Instant,
    val expiresAt: Instant,
    val totalElements: Int,
    val items: List<PreregistrationTarget>,
    val nextCursor: String?,
)
