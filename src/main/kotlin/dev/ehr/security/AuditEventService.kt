package dev.ehr.security

import dev.ehr.runtime.CorrelationIdFilter
import org.slf4j.MDC
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AuditEventService(
    private val auditEventRepository: AuditEventRepository,
) {
    @Transactional
    fun recordPolicyDecision(decision: PolicyDecision): AuditEventRecord =
        auditEventRepository.append(
            AuditEventCommand(
                organizationId = decision.organizationId,
                subjectUserId = decision.subjectUserId,
                resourceType = decision.resourceType.name,
                operation = if (decision.allowed) AuditOperation.READ else AuditOperation.AUTHORIZATION_DENIED,
                outcome = if (decision.allowed) AuditOutcome.SUCCESS else AuditOutcome.DENIED,
                policyVersion = decision.policyVersion,
                policyReasonCode = decision.reasonCode.name,
                correlationId = MDC.get(CorrelationIdFilter.MDC_KEY),
            ),
        )

    @Transactional
    fun recordResourceAccess(
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
                patientId = patientId,
                resourceType = decision.resourceType.name,
                resourceId = resourceId,
                operation = operation,
                outcome = outcome,
                policyVersion = decision.policyVersion,
                policyReasonCode = decision.reasonCode.name,
                correlationId = MDC.get(CorrelationIdFilter.MDC_KEY),
            ),
        )

    @Transactional
    fun recordDeniedAccess(
        decision: PolicyDecision,
        patientId: UUID? = null,
        resourceId: UUID? = null,
    ): AuditEventRecord =
        recordResourceAccess(
            decision = decision,
            operation = AuditOperation.AUTHORIZATION_DENIED,
            outcome = AuditOutcome.DENIED,
            patientId = patientId,
            resourceId = resourceId,
        )
}
