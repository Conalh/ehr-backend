package dev.ehr.note

import dev.ehr.encounter.EncounterId
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import dev.ehr.patient.PatientId
import dev.ehr.terminology.CodeableConceptId
import java.time.Instant

data class ClinicalNote(
    val id: ClinicalNoteId,
    val organizationId: OrganizationId,
    val patientId: PatientId,
    val encounterId: EncounterId,
    val status: ClinicalNoteStatus,
    val typeConceptId: CodeableConceptId,
    val title: String,
    val contentText: String,
    val authoredAt: Instant,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UserId?,
    val updatedBy: UserId?,
)

data class ClinicalNoteCreateCommand(
    val organizationId: OrganizationId,
    val patientId: PatientId,
    val encounterId: EncounterId,
    val typeConceptId: CodeableConceptId,
    val title: String,
    val contentText: String,
    val createdBy: UserId? = null,
)
