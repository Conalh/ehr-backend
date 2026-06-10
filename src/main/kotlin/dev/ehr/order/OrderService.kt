package dev.ehr.order

import dev.ehr.encounter.EncounterRepository
import dev.ehr.identity.TenantScope
import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientRepository
import dev.ehr.provenance.ProvenanceActivity
import dev.ehr.provenance.ProvenanceRecorder
import dev.ehr.security.AuditEventService
import dev.ehr.security.AuditOperation
import dev.ehr.security.AuditOutcome
import dev.ehr.security.PolicyDecision
import dev.ehr.security.PolicyEvaluationRequest
import dev.ehr.security.PolicyEvaluator
import dev.ehr.security.PolicyOperation
import dev.ehr.security.PolicyResourceType
import dev.ehr.security.SecurityPrincipal
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class OrderService(
    private val policyEvaluator: PolicyEvaluator,
    private val auditEventService: AuditEventService,
    private val orderRepository: OrderRepository,
    private val patientRepository: PatientRepository,
    private val encounterRepository: EncounterRepository,
    private val provenanceRecorder: ProvenanceRecorder,
    private val transactionTemplate: TransactionTemplate,
) {
    fun place(
        principal: SecurityPrincipal,
        command: OrderCreateCommand,
    ): Order {
        val decision = evaluate(principal, PolicyOperation.WRITE)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, patientId = command.patientId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to place orders")
        }

        val scope = tenantScope(principal)
        if (command.encounterId != null && encounterRepository.findById(scope, command.encounterId) == null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Encounter not found")
        }

        try {
            return transactionTemplate.execute {
                val order = orderRepository.create(command)
                provenanceRecorder.recordCreated(
                    principal = principal,
                    patientId = order.patientId.value,
                    targetResourceType = "ORDER",
                    targetResourceId = order.id.value,
                )
                auditEventService.recordResourceAccess(
                    decision = decision,
                    operation = AuditOperation.CREATE,
                    outcome = AuditOutcome.SUCCESS,
                    patientId = order.patientId.value,
                    resourceId = order.id.value,
                )
                order
            }!!
        } catch (exception: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")
        } catch (exception: DataIntegrityViolationException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Order code concept is unknown")
        }
    }

    fun get(
        principal: SecurityPrincipal,
        orderId: OrderId,
    ): Order {
        val decision = evaluate(principal, PolicyOperation.READ)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, resourceId = orderId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to read orders")
        }

        val order = orderRepository.findById(tenantScope(principal), orderId)
        if (order == null) {
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.READ,
                outcome = AuditOutcome.FAILURE,
                resourceId = orderId.value,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found")
        }

        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.READ,
            outcome = AuditOutcome.SUCCESS,
            patientId = order.patientId.value,
            resourceId = order.id.value,
        )
        return order
    }

    fun listForPatient(
        principal: SecurityPrincipal,
        patientId: PatientId,
    ): List<Order> {
        val decision = evaluate(principal, PolicyOperation.READ)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, patientId = patientId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to read orders")
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

        val orders = orderRepository.findByPatient(scope, patientId)
        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.SEARCH,
            outcome = AuditOutcome.SUCCESS,
            patientId = patientId.value,
        )
        return orders
    }

    fun transition(
        principal: SecurityPrincipal,
        orderId: OrderId,
        command: OrderTransitionCommand,
    ): Order {
        val decision = evaluate(principal, PolicyOperation.WRITE)
        if (!decision.allowed) {
            auditEventService.recordDeniedAccess(decision, resourceId = orderId.value)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to update orders")
        }

        val scope = tenantScope(principal)
        try {
            return transactionTemplate.execute {
                val prior = orderRepository.findById(scope, orderId)
                    ?: throw OrderNotFoundForTransition()
                val order = orderRepository.transition(scope, orderId, command)
                    ?: throw OrderNotFoundForTransition()
                provenanceRecorder.recordUpdated(
                    principal = principal,
                    patientId = order.patientId.value,
                    targetResourceType = "ORDER",
                    targetResourceId = order.id.value,
                    newVersion = order.version,
                    priorVersion = prior.version,
                    priorState = prior,
                    activity = if (command.targetStatus == OrderStatus.ENTERED_IN_ERROR) {
                        ProvenanceActivity.ENTERED_IN_ERROR
                    } else {
                        ProvenanceActivity.UPDATED
                    },
                )
                auditEventService.recordResourceAccess(
                    decision = decision,
                    operation = AuditOperation.UPDATE,
                    outcome = AuditOutcome.SUCCESS,
                    patientId = order.patientId.value,
                    resourceId = order.id.value,
                )
                order
            }!!
        } catch (exception: OrderNotFoundForTransition) {
            recordFailedTransition(decision, orderId.value)
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found")
        } catch (exception: IllegalArgumentException) {
            recordFailedTransition(decision, orderId.value)
            throw ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                exception.message ?: "Order transition is not allowed",
            )
        } catch (exception: StaleOrderTransitionException) {
            recordFailedTransition(decision, orderId.value)
            throw ResponseStatusException(HttpStatus.CONFLICT, "Order was modified concurrently")
        }
    }

    private fun recordFailedTransition(
        decision: PolicyDecision,
        orderId: UUID,
    ) {
        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.UPDATE,
            outcome = AuditOutcome.FAILURE,
            resourceId = orderId,
        )
    }

    private fun evaluate(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
    ) = policyEvaluator.evaluate(
        principal = principal,
        request = PolicyEvaluationRequest(
            resourceType = PolicyResourceType.ORDER,
            operation = operation,
            organizationId = principal.organization.organizationId,
        ),
    )

    private fun tenantScope(principal: SecurityPrincipal): TenantScope =
        TenantScope(principal.organization.organizationId)

    private class OrderNotFoundForTransition : RuntimeException()
}
