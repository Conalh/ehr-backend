package dev.ehr.patient

import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import dev.ehr.terminology.CodeableConceptId
import java.time.Instant
import java.time.LocalDate

data class Patient(
    val id: PatientId,
    val organizationId: OrganizationId,
    val status: PatientStatus,
    val givenName: String,
    val familyName: String,
    val birthDate: LocalDate?,
    val administrativeGender: PatientAdministrativeGender?,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UserId?,
    val updatedBy: UserId?,
)

data class PatientIdentifier(
    val id: PatientIdentifierId,
    val organizationId: OrganizationId,
    val patientId: PatientId,
    val system: String,
    val value: String,
    val use: IdentifierUse?,
    val typeConceptId: CodeableConceptId?,
    val assignerText: String?,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
    val createdAt: Instant,
)

data class PatientCreateCommand(
    val organizationId: OrganizationId,
    val givenName: String,
    val familyName: String,
    val birthDate: LocalDate? = null,
    val administrativeGender: PatientAdministrativeGender? = null,
    val status: PatientStatus = PatientStatus.ACTIVE,
    val createdBy: UserId? = null,
    val updatedBy: UserId? = null,
)

data class PatientIdentifierCreateCommand(
    val system: String,
    val value: String,
    val use: IdentifierUse? = null,
    val typeConceptId: CodeableConceptId? = null,
    val assignerText: String? = null,
    val periodStart: LocalDate? = null,
    val periodEnd: LocalDate? = null,
)
