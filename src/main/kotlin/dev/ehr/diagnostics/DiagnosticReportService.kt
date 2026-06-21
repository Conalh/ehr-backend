package dev.ehr.diagnostics

import dev.ehr.encounter.EncounterRepository
import dev.ehr.observation.ObservationId
import dev.ehr.observation.ObservationRepository
import dev.ehr.order.OrderId
import dev.ehr.order.OrderRepository
import dev.ehr.order.OrderStatus
import dev.ehr.order.OrderTransitionCommand
import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientAccessGuard
import dev.ehr.provenance.ProvenanceActivity
import dev.ehr.provenance.ProvenanceRecorder
import dev.ehr.security.AuditEventService
import dev.ehr.security.AuditOperation
import dev.ehr.security.AccessAuthorizer
import dev.ehr.security.PolicyOperation
import dev.ehr.security.PolicyResourceType
import dev.ehr.security.SecurityPrincipal
import dev.ehr.security.tenantScope
import dev.ehr.terminology.CodeableConceptId
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException

@Service
class DiagnosticReportService(
    private val accessAuthorizer: AccessAuthorizer,
    private val auditEventService: AuditEventService,
    private val diagnosticReportRepository: DiagnosticReportRepository,
    private val orderRepository: OrderRepository,
    private val observationRepository: ObservationRepository,
    private val encounterRepository: EncounterRepository,
    private val patientAccessGuard: PatientAccessGuard,
    private val provenanceRecorder: ProvenanceRecorder,
    private val transactionTemplate: TransactionTemplate,
) {
    fun attachResult(
        principal: SecurityPrincipal,
        orderId: OrderId,
        codeConceptId: CodeableConceptId,
        resultObservationIds: List<ObservationId>,
        conclusionText: String?,
        encounterId: dev.ehr.encounter.EncounterId?,
    ): DiagnosticReport {
        authorize(
            principal = principal,
            operation = PolicyOperation.WRITE,
            forbiddenMessage = "Not authorized to attach results",
            resourceId = orderId.value,
        )
        if (resultObservationIds.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one result observation is required")
        }
        if (conclusionText != null && conclusionText.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Conclusion text must not be blank")
        }

        val scope = principal.tenantScope()
        val order = orderRepository.findById(scope, orderId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found")
        if (order.status != OrderStatus.ACTIVE) {
            throw ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Results can only be attached to active orders",
            )
        }
        if (encounterId != null) {
            val encounter = encounterRepository.findById(scope, encounterId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Encounter not found")
            if (encounter.patientId != order.patientId) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Encounter does not belong to the order's patient")
            }
        }
        resultObservationIds.forEach { observationId ->
            val observation = observationRepository.findById(scope, observationId)
            if (observation == null || observation.patientId != order.patientId) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Result observations must belong to the order's patient",
                )
            }
        }
        // Re-evaluate with the discovered patient: in enforced organizations
        // a missing treatment relationship denies here.
        val compartmentDecision = authorize(
            principal = principal,
            operation = PolicyOperation.WRITE,
            forbiddenMessage = "Not authorized to attach results",
            patientId = order.patientId.value,
            resourceId = orderId.value,
        )

        try {
            return transactionTemplate.execute {
                val report = diagnosticReportRepository.create(
                    DiagnosticReportCreateCommand(
                        organizationId = order.organizationId,
                        patientId = order.patientId,
                        orderId = order.id,
                        codeConceptId = codeConceptId,
                        resultObservationIds = resultObservationIds,
                        encounterId = encounterId,
                        conclusionText = conclusionText,
                        createdBy = principal.subject.userId,
                    ),
                )
                val completedOrder = orderRepository.transition(
                    scope,
                    order.id,
                    OrderTransitionCommand(
                        targetStatus = OrderStatus.COMPLETED,
                        expectedVersion = order.version,
                        updatedBy = principal.subject.userId,
                    ),
                ) ?: throw IllegalStateException("order disappeared during result attachment")
                provenanceRecorder.recordUpdated(
                    principal = principal,
                    patientId = order.patientId.value,
                    targetResourceType = "ORDER",
                    targetResourceId = order.id.value,
                    newVersion = completedOrder.version,
                    priorVersion = order.version,
                    priorState = order,
                    activity = ProvenanceActivity.UPDATED,
                )
                provenanceRecorder.recordCreated(
                    principal = principal,
                    patientId = report.patientId.value,
                    targetResourceType = "DIAGNOSTIC_REPORT",
                    targetResourceId = report.id.value,
                )
                auditEventService.recordSuccessfulAccess(
                    decision = compartmentDecision,
                    operation = AuditOperation.CREATE,
                    patientId = report.patientId.value,
                    resourceId = report.id.value,
                )
                report
            }!!
        } catch (exception: DataIntegrityViolationException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Report code concept is unknown")
        }
    }

    fun get(
        principal: SecurityPrincipal,
        reportId: DiagnosticReportId,
    ): DiagnosticReport {
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read diagnostic reports",
            resourceId = reportId.value,
        )

        val report = diagnosticReportRepository.findById(principal.tenantScope(), reportId)
        if (report == null) {
            auditEventService.recordFailedAccess(
                decision = decision,
                operation = AuditOperation.READ,
                resourceId = reportId.value,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Diagnostic report not found")
        }

        // Re-evaluate with the discovered patient: in enforced organizations
        // a missing treatment relationship denies here.
        val compartmentDecision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read diagnostic reports",
            patientId = report.patientId.value,
            resourceId = report.id.value,
        )
        auditEventService.recordSuccessfulAccess(
            decision = compartmentDecision,
            operation = AuditOperation.READ,
            patientId = report.patientId.value,
            resourceId = report.id.value,
        )
        return report
    }

    fun listForPatient(
        principal: SecurityPrincipal,
        patientId: PatientId,
    ): List<DiagnosticReport> {
        val visibilityDecision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read diagnostic reports",
        )

        val scope = principal.tenantScope()
        patientAccessGuard.requirePatientForSearch(scope, patientId, visibilityDecision)
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read diagnostic reports",
            patientId = patientId.value,
        )

        val reports = diagnosticReportRepository.findByPatient(scope, patientId)
        auditEventService.recordSuccessfulAccess(
            decision = decision,
            operation = AuditOperation.SEARCH,
            patientId = patientId.value,
        )
        return reports
    }

    private fun authorize(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
        patientId: java.util.UUID? = null,
        resourceId: java.util.UUID? = null,
        forbiddenMessage: String,
    ) = accessAuthorizer.authorize(
        principal = principal,
        resourceType = PolicyResourceType.DIAGNOSTIC_REPORT,
        operation = operation,
        forbiddenMessage = forbiddenMessage,
        patientId = patientId,
        resourceId = resourceId,
    )

}
