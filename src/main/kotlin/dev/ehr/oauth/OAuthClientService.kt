package dev.ehr.oauth

import dev.ehr.identity.OAuthClient
import dev.ehr.identity.OAuthClientId
import dev.ehr.identity.OAuthClientRepository
import dev.ehr.identity.TenantScope
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
class OAuthClientService(
    private val policyEvaluator: PolicyEvaluator,
    private val auditEventService: AuditEventService,
    private val oauthClientRepository: OAuthClientRepository,
    private val transactionTemplate: TransactionTemplate,
) {
    fun register(
        principal: SecurityPrincipal,
        clientIdentifier: String,
        displayName: String,
    ): OAuthClient {
        val decision = evaluate(principal, PolicyOperation.WRITE)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to register clients")
        }
        if (clientIdentifier.isBlank() || displayName.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Client identifier and display name must not be blank")
        }

        try {
            return transactionTemplate.execute {
                val client = oauthClientRepository.create(
                    organizationId = principal.organization.organizationId,
                    clientIdentifier = clientIdentifier,
                    displayName = displayName,
                )
                auditEventService.recordResourceAccess(
                    decision = decision,
                    operation = AuditOperation.CREATE,
                    outcome = AuditOutcome.SUCCESS,
                    resourceId = client.id.value,
                )
                client
            }!!
        } catch (exception: DuplicateKeyException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Client identifier already exists")
        }
    }

    fun get(
        principal: SecurityPrincipal,
        clientId: OAuthClientId,
    ): OAuthClient {
        val decision = evaluate(principal, PolicyOperation.READ)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, resourceId = clientId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to read clients")
        }

        val client = oauthClientRepository.findById(tenantScope(principal), clientId)
        if (client == null) {
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.READ,
                outcome = AuditOutcome.FAILURE,
                resourceId = clientId.value,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Client not found")
        }

        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.READ,
            outcome = AuditOutcome.SUCCESS,
            resourceId = client.id.value,
        )
        return client
    }

    fun list(principal: SecurityPrincipal): List<OAuthClient> {
        val decision = evaluate(principal, PolicyOperation.READ)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to list clients")
        }

        val clients = oauthClientRepository.findByOrganization(tenantScope(principal))
        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.SEARCH,
            outcome = AuditOutcome.SUCCESS,
        )
        return clients
    }

    fun revoke(
        principal: SecurityPrincipal,
        clientId: OAuthClientId,
    ): OAuthClient {
        val decision = evaluate(principal, PolicyOperation.WRITE)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, resourceId = clientId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to revoke clients")
        }

        val scope = tenantScope(principal)
        val existing = oauthClientRepository.findById(scope, clientId)
        if (existing == null) {
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.UPDATE,
                outcome = AuditOutcome.FAILURE,
                resourceId = clientId.value,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Client not found")
        }

        return transactionTemplate.execute {
            val revoked = oauthClientRepository.revoke(scope, clientId)
                ?: throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Client is already revoked")
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.UPDATE,
                outcome = AuditOutcome.SUCCESS,
                resourceId = revoked.id.value,
            )
            revoked
        }!!
    }

    private fun evaluate(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
    ) = policyEvaluator.evaluate(
        principal = principal,
        request = PolicyEvaluationRequest(
            resourceType = PolicyResourceType.OAUTH_CLIENT,
            operation = operation,
            organizationId = principal.organization.organizationId,
        ),
    )

    private fun tenantScope(principal: SecurityPrincipal): TenantScope =
        TenantScope(principal.organization.organizationId)
}
