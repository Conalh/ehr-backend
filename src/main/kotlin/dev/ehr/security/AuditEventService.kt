package dev.ehr.security

import dev.ehr.runtime.CorrelationIdFilter
import org.slf4j.MDC
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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
}
