package dev.ehr.careteam

import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.UserId
import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientAccessGuard
import dev.ehr.security.AccessAuthorizer
import dev.ehr.security.AuditEventService
import dev.ehr.security.AuditOperation
import dev.ehr.security.PolicyOperation
import dev.ehr.security.PolicyResourceType
import dev.ehr.security.SecurityPrincipal
import dev.ehr.security.tenantScope
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException

@Service
class CareTeamService(
    private val accessAuthorizer: AccessAuthorizer,
    private val auditEventService: AuditEventService,
    private val careTeamRepository: CareTeamRepository,
    private val patientAccessGuard: PatientAccessGuard,
    private val membershipRepository: MembershipRepository,
    private val transactionTemplate: TransactionTemplate,
) {
    fun addMember(
        principal: SecurityPrincipal,
        patientId: PatientId,
        userId: UserId,
        role: CareTeamRole,
    ): CareTeamMembership {
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.WRITE,
            forbiddenMessage = "Not authorized to manage care teams",
            patientId = patientId.value,
        )
        if (membershipRepository.findActiveByOrganizationAndUser(
                principal.organization.organizationId,
                userId,
            ) == null
        ) {
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
                auditEventService.recordSuccessfulAccess(
                    decision = decision,
                    operation = AuditOperation.CREATE,
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
        val visibilityDecision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read care teams",
        )

        val scope = principal.tenantScope()
        patientAccessGuard.requirePatientForSearch(scope, patientId, visibilityDecision)
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read care teams",
            patientId = patientId.value,
        )

        val members = careTeamRepository.findActiveByPatient(scope, patientId)
        auditEventService.recordSuccessfulAccess(
            decision = decision,
            operation = AuditOperation.SEARCH,
            patientId = patientId.value,
        )
        return members
    }

    fun endMembership(
        principal: SecurityPrincipal,
        membershipId: CareTeamMembershipId,
    ): CareTeamMembership {
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.WRITE,
            forbiddenMessage = "Not authorized to manage care teams",
            resourceId = membershipId.value,
        )

        val scope = principal.tenantScope()
        val existing = careTeamRepository.findById(scope, membershipId)
            ?: run {
                auditEventService.recordFailedAccess(
                    decision = decision,
                    operation = AuditOperation.UPDATE,
                    resourceId = membershipId.value,
                )
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Membership not found")
            }

        // Re-evaluate with the discovered patient: launch-bound tokens are
        // denied outside their patient context here.
        val compartmentDecision = authorize(
            principal = principal,
            operation = PolicyOperation.WRITE,
            forbiddenMessage = "Not authorized to manage care teams",
            patientId = existing.patientId.value,
            resourceId = membershipId.value,
        )

        return transactionTemplate.execute {
            val ended = careTeamRepository.end(scope, membershipId)
                ?: throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Membership is already ended")
            auditEventService.recordSuccessfulAccess(
                decision = compartmentDecision,
                operation = AuditOperation.UPDATE,
                patientId = existing.patientId.value,
                resourceId = ended.id.value,
            )
            ended
        }!!
    }

    private fun authorize(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
        forbiddenMessage: String,
        patientId: java.util.UUID? = null,
        resourceId: java.util.UUID? = null,
    ) = accessAuthorizer.authorize(
        principal = principal,
        resourceType = PolicyResourceType.CARE_TEAM,
        operation = operation,
        forbiddenMessage = forbiddenMessage,
        patientId = patientId,
        resourceId = resourceId,
    )

}
