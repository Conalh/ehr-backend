package dev.ehr.careteam

import dev.ehr.identity.TenantScope
import dev.ehr.identity.UserId
import dev.ehr.identity.UserRepository
import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientRepository
import dev.ehr.security.AuditEventService
import dev.ehr.security.AuditOperation
import dev.ehr.security.AuditOutcome
import dev.ehr.security.PolicyEvaluationRequest
import dev.ehr.security.PolicyEvaluator
import dev.ehr.security.PolicyOperation
import dev.ehr.security.PolicyResourceType
import dev.ehr.security.SecurityPrincipal
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException

@Service
class CareTeamService(
    private val policyEvaluator: PolicyEvaluator,
    private val auditEventService: AuditEventService,
    private val careTeamRepository: CareTeamRepository,
    private val patientRepository: PatientRepository,
    private val userRepository: UserRepository,
    private val transactionTemplate: TransactionTemplate,
) {
    fun addMember(
        principal: SecurityPrincipal,
        patientId: PatientId,
        userId: UserId,
        role: CareTeamRole,
    ): CareTeamMembership {
        val decision = evaluate(principal, PolicyOperation.WRITE, patientId.value)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, patientId = patientId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to manage care teams")
        }
        if (userRepository.findById(userId) == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown user")
        }

        try {
            return transactionTemplate.execute {
                val membership = careTeamRepository.addMember(
                    organizationId = principal.organization.organizationId,
                    patientId = patientId,
                    userId = userId,
                    role = role,
                    origin = CareTeamMembershipOrigin.EXPLICIT,
                    createdBy = principal.subject.userId,
                )
                auditEventService.recordResourceAccess(
                    decision = decision,
                    operation = AuditOperation.CREATE,
                    outcome = AuditOutcome.SUCCESS,
                    patientId = membership.patientId.value,
                    resourceId = membership.id.value,
                )
                membership
            }!!
        } catch (exception: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")
        } catch (exception: DuplicateKeyException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "An active membership with this role already exists")
        }
    }

    fun listForPatient(
        principal: SecurityPrincipal,
        patientId: PatientId,
    ): List<CareTeamMembership> {
        val decision = evaluate(principal, PolicyOperation.READ, patientId.value)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, patientId = patientId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to read care teams")
        }

        val scope = tenantScope(principal)
        if (patientRepository.findById(scope, patientId) == null) {
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.SEARCH,
                outcome = AuditOutcome.FAILURE,
                resourceId = patientId.value,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")
        }

        val members = careTeamRepository.findActiveByPatient(scope, patientId)
        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.SEARCH,
            outcome = AuditOutcome.SUCCESS,
            patientId = patientId.value,
        )
        return members
    }

    fun endMembership(
        principal: SecurityPrincipal,
        membershipId: CareTeamMembershipId,
    ): CareTeamMembership {
        val decision = evaluate(principal, PolicyOperation.WRITE)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, resourceId = membershipId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to manage care teams")
        }

        val scope = tenantScope(principal)
        val existing = careTeamRepository.findById(scope, membershipId)
            ?: run {
                auditEventService.recordResourceAccess(
                    decision = decision,
                    operation = AuditOperation.UPDATE,
                    outcome = AuditOutcome.FAILURE,
                    resourceId = membershipId.value,
                )
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Membership not found")
            }

        // Re-evaluate with the discovered patient: launch-bound tokens are
        // denied outside their patient context here.
        val compartmentDecision = evaluate(principal, PolicyOperation.WRITE, existing.patientId.value)
        if (!compartmentDecision.allowed) {
            auditEventService.recordDeniedAccess(
                compartmentDecision,
                patientId = existing.patientId.value,
                resourceId = membershipId.value,
            )
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to manage care teams")
        }

        return transactionTemplate.execute {
            val ended = careTeamRepository.end(scope, membershipId)
                ?: throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Membership is already ended")
            auditEventService.recordResourceAccess(
                decision = compartmentDecision,
                operation = AuditOperation.UPDATE,
                outcome = AuditOutcome.SUCCESS,
                patientId = existing.patientId.value,
                resourceId = ended.id.value,
            )
            ended
        }!!
    }

    private fun evaluate(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
        patientId: java.util.UUID? = null,
    ) = policyEvaluator.evaluate(
        principal = principal,
        request = PolicyEvaluationRequest(
            resourceType = PolicyResourceType.CARE_TEAM,
            operation = operation,
            organizationId = principal.organization.organizationId,
            patientId = patientId,
        ),
    )

    private fun tenantScope(principal: SecurityPrincipal): TenantScope =
        TenantScope(principal.organization.organizationId)
}
