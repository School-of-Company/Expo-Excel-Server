package team.startup.report.global.client.application

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class FieldVisitStatus { ACTIVE, CANCELLED }

data class FieldVisit(
    val id: UUID,
    val expoId: UUID,
    val participantId: Long,
    val visitDate: LocalDate,
    val status: FieldVisitStatus,
    val generation: Int,
    val registeredAt: Instant,
    val cancelledAt: Instant?,
    val cancelledBy: Long?,
    @param:JsonProperty(required = true)
    @param:JsonSetter(nulls = Nulls.FAIL)
    val cancellationPending: Boolean,
    @param:JsonProperty(required = true)
    @param:JsonSetter(nulls = Nulls.FAIL)
    val deliveryPending: Boolean,
) {
    val key get() = "FIELD:$expoId:$id"
}

data class FieldVisitEvent(
    val registrationId: UUID,
    val generation: Int,
    val eventType: FieldVisitEventType,
    val occurredAt: Instant,
    val actorParticipantId: Long?,
    val operationId: UUID,
)

enum class FieldVisitEventType { REGISTERED, CANCELLED }

data class FieldVisitPage(
    val items: List<FieldVisit>,
    val events: List<FieldVisitEvent>,
    val nextCursor: UUID?,
)
