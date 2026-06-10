package dev.ehr.observation

import dev.ehr.encounter.EncounterId
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import dev.ehr.patient.PatientId
import dev.ehr.terminology.CodeableConceptId
import java.math.BigDecimal
import java.time.Instant

// Exactly one value shape per observation; the schema enforces the same rule.
sealed interface ObservationValue {
    data class Quantity(
        val value: BigDecimal,
        val unit: String,
    ) : ObservationValue {
        init {
            require(unit.isNotBlank()) { "quantity unit must not be blank" }
        }
    }

    data class Coded(
        val conceptId: CodeableConceptId,
    ) : ObservationValue

    data class Text(
        val value: String,
    ) : ObservationValue {
        init {
            require(value.isNotBlank()) { "text value must not be blank" }
        }
    }
}

data class Observation(
    val id: ObservationId,
    val organizationId: OrganizationId,
    val patientId: PatientId,
    val encounterId: EncounterId?,
    val status: ObservationStatus,
    val category: ObservationCategory,
    val codeConceptId: CodeableConceptId,
    val value: ObservationValue,
    val effectiveAt: Instant,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UserId?,
    val updatedBy: UserId?,
)

data class ObservationCreateCommand(
    val organizationId: OrganizationId,
    val patientId: PatientId,
    val category: ObservationCategory,
    val codeConceptId: CodeableConceptId,
    val value: ObservationValue,
    val effectiveAt: Instant,
    val encounterId: EncounterId? = null,
    val status: ObservationStatus = ObservationStatus.FINAL,
    val createdBy: UserId? = null,
)
