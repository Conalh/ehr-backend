package dev.ehr.security

import com.fasterxml.jackson.databind.ObjectMapper
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import dev.ehr.runtime.CorrelationIdFilter
import org.slf4j.MDC
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AuditEventService(
    private val auditEventRepository: AuditEventRepository,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun recordPolicyDecision(decision: PolicyDecision): AuditEventRecord =
        auditEventRepository.append(
            AuditEventCommand(
                organizationId = decision.organizationId,
                subjectUserId = decision.subjectUserId,
                clientId = decision.clientId,
                resourceType = decision.resourceType.name,
                operation = if (decision.allowed) AuditOperation.READ else AuditOperation.AUTHORIZATION_DENIED,
                outcome = if (decision.allowed) AuditOutcome.SUCCESS else AuditOutcome.DENIED,
                policyVersion = decision.policyVersion,
                policyReasonCode = decision.reasonCode.name,
                relationshipBasis = decision.relationshipBasis?.dbValue,
                purposeOfUse = decision.purposeOfUse,
                correlationId = MDC.get(CorrelationIdFilter.MDC_KEY),
                metadata = decisionMetadata(decision),
            ),
        )

    @Transactional
    fun recordSuccessfulAccess(
        decision: PolicyDecision,
        operation: AuditOperation,
        patientId: UUID? = null,
        resourceId: UUID? = null,
    ): AuditEventRecord =
        appendResourceAccess(
            decision = decision,
            operation = operation,
            outcome = AuditOutcome.SUCCESS,
            patientId = patientId,
            resourceId = resourceId,
        )

    @Transactional
    fun recordFailedAccess(
        decision: PolicyDecision,
        operation: AuditOperation,
        patientId: UUID? = null,
        resourceId: UUID? = null,
    ): AuditEventRecord =
        appendResourceAccess(
            decision = decision,
            operation = operation,
            outcome = AuditOutcome.FAILURE,
            patientId = patientId,
            resourceId = resourceId,
        )

    private fun appendResourceAccess(
        decision: PolicyDecision,
        operation: AuditOperation,
        outcome: AuditOutcome,
        patientId: UUID? = null,
        resourceId: UUID? = null,
    ): AuditEventRecord =
        auditEventRepository.append(
            AuditEventCommand(
                organizationId = decision.organizationId,
                subjectUserId = decision.subjectUserId,
                clientId = decision.clientId,
                patientId = patientId,
                resourceType = decision.resourceType.name,
                resourceId = resourceId,
                operation = operation,
                outcome = outcome,
                policyVersion = decision.policyVersion,
                policyReasonCode = decision.reasonCode.name,
                relationshipBasis = decision.relationshipBasis?.dbValue,
                purposeOfUse = decision.purposeOfUse,
                correlationId = MDC.get(CorrelationIdFilter.MDC_KEY),
                metadata = decisionMetadata(decision),
            ),
        )

    private fun decisionMetadata(decision: PolicyDecision): String {
        val reason = decision.breakGlassReason ?: return "{}"
        return objectMapper.writeValueAsString(mapOf("breakGlassReason" to reason))
    }

    /**
     * For background workers (e.g. the export processor) acting on behalf of a
     * recorded requester without a live security principal or policy decision.
     *
     * [correlationId] defaults to the ambient request correlation, which is
     * what request-thread callers (e.g. the patient picker) want. Work that
     * runs on an async executor must pass `correlationId = null`: the export
     * task decorator propagates the originating request's correlation into the
     * worker's MDC for log tracing, but adopting it here would make every
     * file-creation event collide with the kickoff's audit row, breaking the
     * one-event-per-correlation invariant. Such events are keyed by requester
     * and resource instead.
     */
    @Transactional
    fun recordBackgroundEvent(
        organizationId: OrganizationId,
        subjectUserId: UserId?,
        resourceType: String,
        operation: AuditOperation,
        outcome: AuditOutcome,
        resourceId: UUID? = null,
        correlationId: String? = MDC.get(CorrelationIdFilter.MDC_KEY),
    ): AuditEventRecord =
        auditEventRepository.append(
            AuditEventCommand(
                organizationId = organizationId,
                subjectUserId = subjectUserId,
                resourceType = resourceType,
                resourceId = resourceId,
                operation = operation,
                outcome = outcome,
                policyVersion = null,
                policyReasonCode = null,
                correlationId = correlationId,
            ),
        )

    @Transactional
    fun recordDeniedAccess(
        decision: PolicyDecision,
        patientId: UUID? = null,
        resourceId: UUID? = null,
    ): AuditEventRecord =
        appendResourceAccess(
            decision = decision,
            operation = AuditOperation.AUTHORIZATION_DENIED,
            outcome = AuditOutcome.DENIED,
            patientId = patientId,
            resourceId = resourceId,
        )
}
