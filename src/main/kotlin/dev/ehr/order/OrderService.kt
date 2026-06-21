package dev.ehr.order

import dev.ehr.encounter.EncounterRepository
import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientRepository
import dev.ehr.provenance.ProvenanceActivity
import dev.ehr.provenance.ProvenanceRecorder
import dev.ehr.security.AuditEventService
import dev.ehr.security.AuditOperation
import dev.ehr.security.AuditOutcome
import dev.ehr.security.AccessAuthorizer
import dev.ehr.security.CompartmentDeniedException
import dev.ehr.security.PolicyDecision
import dev.ehr.security.PolicyOperation
import dev.ehr.security.PolicyResourceType
import dev.ehr.security.SecurityPrincipal
import dev.ehr.security.tenantScope
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class OrderService(
    private val accessAuthorizer: AccessAuthorizer,
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
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.WRITE,
            forbiddenMessage = "Not authorized to place orders",
            patientId = command.patientId.value,
        )

        val scope = principal.tenantScope()
        if (command.encounterId != null) {
            val encounter = encounterRepository.findById(scope, command.encounterId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Encounter not found")
            if (encounter.patientId != command.patientId) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Encounter does not belong to patient")
            }
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
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read orders",
            resourceId = orderId.value,
        )

        val order = orderRepository.findById(principal.tenantScope(), orderId)
        if (order == null) {
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.READ,
                outcome = AuditOutcome.FAILURE,
                resourceId = orderId.value,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found")
        }

        // Re-evaluate with the discovered patient: in enforced organizations
        // a missing treatment relationship denies here.
        val compartmentDecision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read orders",
            patientId = order.patientId.value,
            resourceId = order.id.value,
        )
        auditEventService.recordResourceAccess(
            decision = compartmentDecision,
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
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read orders",
            patientId = patientId.value,
        )

        val scope = principal.tenantScope()
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
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.WRITE,
            forbiddenMessage = "Not authorized to update orders",
            resourceId = orderId.value,
        )

        val scope = principal.tenantScope()
        try {
            return transactionTemplate.execute {
                val prior = orderRepository.findById(scope, orderId)
                    ?: throw OrderNotFoundForTransition()
                // Re-evaluate with the discovered patient: in enforced
                // organizations a missing treatment relationship denies here,
                // before the mutation. Thrown past the transaction so the
                // denial audit row survives the rollback.
                val compartmentDecision = evaluate(principal, PolicyOperation.WRITE, prior.patientId.value)
                if (!compartmentDecision.allowed) {
                    throw CompartmentDeniedException(compartmentDecision, prior.patientId.value, orderId.value)
                }
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
                    decision = compartmentDecision,
                    operation = AuditOperation.UPDATE,
                    outcome = AuditOutcome.SUCCESS,
                    patientId = order.patientId.value,
                    resourceId = order.id.value,
                )
                order
            }!!
        } catch (exception: CompartmentDeniedException) {
            auditEventService.recordDeniedAccess(
                exception.decision,
                patientId = exception.patientId,
                resourceId = exception.resourceId,
            )
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to update orders")
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

    private fun authorize(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
        patientId: UUID? = null,
        resourceId: UUID? = null,
        forbiddenMessage: String,
    ) = accessAuthorizer.authorize(
        principal = principal,
        resourceType = PolicyResourceType.ORDER,
        operation = operation,
        forbiddenMessage = forbiddenMessage,
        patientId = patientId,
        resourceId = resourceId,
    )

    private fun evaluate(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
        patientId: UUID? = null,
    ) = accessAuthorizer.evaluate(
        principal = principal,
        resourceType = PolicyResourceType.ORDER,
        operation = operation,
        patientId = patientId,
    )


    private class OrderNotFoundForTransition : RuntimeException()
}
