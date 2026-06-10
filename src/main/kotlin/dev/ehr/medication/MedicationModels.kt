package dev.ehr.medication

import dev.ehr.encounter.EncounterId
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import dev.ehr.patient.PatientId
import dev.ehr.terminology.CodeableConceptId
import java.time.Instant
import java.time.LocalDate

data class MedicationStatement(
    val id: MedicationStatementId,
    val organizationId: OrganizationId,
    val patientId: PatientId,
    val encounterId: EncounterId?,
    val status: MedicationStatementStatus,
    val medicationConceptId: CodeableConceptId,
    val dosageText: String?,
    val effectiveStart: LocalDate?,
    val effectiveEnd: LocalDate?,
    val recordedAt: Instant,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UserId?,
    val updatedBy: UserId?,
)

data class MedicationStatementCreateCommand(
    val organizationId: OrganizationId,
    val patientId: PatientId,
    val medicationConceptId: CodeableConceptId,
    val encounterId: EncounterId? = null,
    val status: MedicationStatementStatus = MedicationStatementStatus.ACTIVE,
    val dosageText: String? = null,
    val effectiveStart: LocalDate? = null,
    val effectiveEnd: LocalDate? = null,
    val createdBy: UserId? = null,
)
