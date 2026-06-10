package dev.ehr.allergy

import dev.ehr.encounter.EncounterId
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import dev.ehr.patient.PatientId
import dev.ehr.terminology.CodeableConceptId
import java.time.Instant
import java.time.LocalDate

data class Allergy(
    val id: AllergyId,
    val organizationId: OrganizationId,
    val patientId: PatientId,
    val encounterId: EncounterId?,
    val clinicalStatus: AllergyClinicalStatus,
    val verificationStatus: AllergyVerificationStatus,
    val codeConceptId: CodeableConceptId,
    val category: AllergyCategory?,
    val criticality: AllergyCriticality?,
    val onsetDate: LocalDate?,
    val recordedAt: Instant,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UserId?,
    val updatedBy: UserId?,
)

data class AllergyCreateCommand(
    val organizationId: OrganizationId,
    val patientId: PatientId,
    val codeConceptId: CodeableConceptId,
    val encounterId: EncounterId? = null,
    val clinicalStatus: AllergyClinicalStatus = AllergyClinicalStatus.ACTIVE,
    val verificationStatus: AllergyVerificationStatus = AllergyVerificationStatus.CONFIRMED,
    val category: AllergyCategory? = null,
    val criticality: AllergyCriticality? = null,
    val onsetDate: LocalDate? = null,
    val createdBy: UserId? = null,
)
