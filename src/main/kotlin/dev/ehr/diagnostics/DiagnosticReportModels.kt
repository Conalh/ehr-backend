package dev.ehr.diagnostics

import dev.ehr.encounter.EncounterId
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import dev.ehr.observation.ObservationId
import dev.ehr.order.OrderId
import dev.ehr.patient.PatientId
import dev.ehr.terminology.CodeableConceptId
import java.time.Instant

data class DiagnosticReport(
    val id: DiagnosticReportId,
    val organizationId: OrganizationId,
    val patientId: PatientId,
    val encounterId: EncounterId?,
    val orderId: OrderId,
    val status: DiagnosticReportStatus,
    val codeConceptId: CodeableConceptId,
    val conclusionText: String?,
    val issuedAt: Instant,
    val resultObservationIds: List<ObservationId>,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UserId?,
    val updatedBy: UserId?,
)

data class DiagnosticReportCreateCommand(
    val organizationId: OrganizationId,
    val patientId: PatientId,
    val orderId: OrderId,
    val codeConceptId: CodeableConceptId,
    val resultObservationIds: List<ObservationId>,
    val encounterId: EncounterId? = null,
    val status: DiagnosticReportStatus = DiagnosticReportStatus.FINAL,
    val conclusionText: String? = null,
    val createdBy: UserId? = null,
)
