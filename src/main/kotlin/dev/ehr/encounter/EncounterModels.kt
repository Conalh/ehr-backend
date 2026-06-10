package dev.ehr.encounter

import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import dev.ehr.patient.PatientId
import dev.ehr.terminology.CodeableConceptId
import java.time.Instant

data class Encounter(
    val id: EncounterId,
    val organizationId: OrganizationId,
    val patientId: PatientId,
    val status: EncounterStatus,
    val classConceptId: CodeableConceptId,
    val periodStart: Instant,
    val periodEnd: Instant?,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UserId?,
    val updatedBy: UserId?,
)

data class EncounterCreateCommand(
    val organizationId: OrganizationId,
    val patientId: PatientId,
    val classConceptId: CodeableConceptId,
    val periodStart: Instant,
    val status: EncounterStatus = EncounterStatus.PLANNED,
    val createdBy: UserId? = null,
)

data class EncounterTransitionCommand(
    val targetStatus: EncounterStatus,
    val periodEnd: Instant? = null,
    val updatedBy: UserId? = null,
    // Caller-observed version for optimistic concurrency; null falls back to the current row version.
    val expectedVersion: Int? = null,
)
