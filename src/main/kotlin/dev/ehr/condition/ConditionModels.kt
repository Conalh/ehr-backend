package dev.ehr.condition

import dev.ehr.encounter.EncounterId
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import dev.ehr.patient.PatientId
import dev.ehr.terminology.CodeableConceptId
import java.time.Instant
import java.time.LocalDate

data class Condition(
    val id: ConditionId,
    val organizationId: OrganizationId,
    val patientId: PatientId,
    val encounterId: EncounterId?,
    val clinicalStatus: ConditionClinicalStatus,
    val verificationStatus: ConditionVerificationStatus,
    val codeConceptId: CodeableConceptId,
    val onsetDate: LocalDate?,
    val abatementDate: LocalDate?,
    val recordedAt: Instant,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UserId?,
    val updatedBy: UserId?,
)

data class ConditionCreateCommand(
    val organizationId: OrganizationId,
    val patientId: PatientId,
    val codeConceptId: CodeableConceptId,
    val encounterId: EncounterId? = null,
    val clinicalStatus: ConditionClinicalStatus = ConditionClinicalStatus.ACTIVE,
    val verificationStatus: ConditionVerificationStatus = ConditionVerificationStatus.CONFIRMED,
    val onsetDate: LocalDate? = null,
    val abatementDate: LocalDate? = null,
    val createdBy: UserId? = null,
)
