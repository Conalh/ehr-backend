package dev.ehr.encounter

import dev.ehr.careteam.CareTeamMembershipOrigin
import dev.ehr.careteam.CareTeamRepository
import dev.ehr.careteam.CareTeamRole
import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientRepository
import dev.ehr.provenance.ProvenanceRecorder
import dev.ehr.security.AccessAuthorizer
import dev.ehr.security.AuditEventService
import dev.ehr.security.AuditOperation
import dev.ehr.security.AuditOutcome
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

@Service
class EncounterService(
    private val accessAuthorizer: AccessAuthorizer,
    private val auditEventService: AuditEventService,
    private val encounterRepository: EncounterRepository,
    private val patientRepository: PatientRepository,
    private val careTeamRepository: CareTeamRepository,
    private val provenanceRecorder: ProvenanceRecorder,
    private val transactionTemplate: TransactionTemplate,
) {
    fun open(
        principal: SecurityPrincipal,
        patientId: PatientId,
        command: EncounterCreateCommand,
    ): Encounter {
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.WRITE,
            forbiddenMessage = "Not authorized to open encounters",
            patientId = patientId.value,
        )
        if (command.status != EncounterStatus.PLANNED && command.status != EncounterStatus.IN_PROGRESS) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Encounters can only be opened as planned or in-progress",
            )
        }

        try {
            return transactionTemplate.execute {
                val encounter = encounterRepository.create(command)
                // Opening an encounter establishes a treatment relationship
                // (compartment authorization design, decision 1C).
                principal.subject.userId?.let { actingUserId ->
                    careTeamRepository.ensureMembership(
                        organizationId = encounter.organizationId,
                        patientId = encounter.patientId,
                        userId = actingUserId,
                        role = CareTeamRole.CARE_TEAM,
                        origin = CareTeamMembershipOrigin.ENCOUNTER_DERIVED,
                    )
                }
                provenanceRecorder.recordCreated(
                    principal = principal,
                    patientId = encounter.patientId.value,
                    targetResourceType = "ENCOUNTER",
                    targetResourceId = encounter.id.value,
                )
                auditEventService.recordResourceAccess(
                    decision = decision,
                    operation = AuditOperation.CREATE,
                    outcome = AuditOutcome.SUCCESS,
                    patientId = encounter.patientId.value,
                    resourceId = encounter.id.value,
                )
                encounter
            }!!
        } catch (exception: IllegalArgumentException) {
            // The same-organization insert found no patient row: not visible in this tenant.
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")
        } catch (exception: DataIntegrityViolationException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Encounter class concept is unknown")
        }
    }

    fun get(
        principal: SecurityPrincipal,
        encounterId: EncounterId,
    ): Encounter {
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read encounters",
            resourceId = encounterId.value,
        )

        val encounter = encounterRepository.findById(principal.tenantScope(), encounterId)
        if (encounter == null) {
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.READ,
                outcome = AuditOutcome.FAILURE,
                resourceId = encounterId.value,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Encounter not found")
        }

        // Re-evaluate with the discovered patient: launch-bound tokens are
        // denied outside their patient context here.
        val compartmentDecision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read encounters",
            patientId = encounter.patientId.value,
            resourceId = encounter.id.value,
        )
        auditEventService.recordResourceAccess(
            decision = compartmentDecision,
            operation = AuditOperation.READ,
            outcome = AuditOutcome.SUCCESS,
            patientId = encounter.patientId.value,
            resourceId = encounter.id.value,
        )
        return encounter
    }

    fun timeline(
        principal: SecurityPrincipal,
        patientId: PatientId,
    ): List<Encounter> {
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read encounters",
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

        val encounters = encounterRepository.findByPatient(scope, patientId)
        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.SEARCH,
            outcome = AuditOutcome.SUCCESS,
            patientId = patientId.value,
        )
        return encounters
    }

    fun transition(
        principal: SecurityPrincipal,
        encounterId: EncounterId,
        command: EncounterTransitionCommand,
    ): Encounter {
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.WRITE,
            forbiddenMessage = "Not authorized to update encounters",
            resourceId = encounterId.value,
        )

        val scope = principal.tenantScope()
        try {
            return transactionTemplate.execute {
                val prior = encounterRepository.findById(scope, encounterId)
                    ?: throw EncounterNotFoundForTransition()
                // Re-evaluate with the discovered patient: launch-bound
                // tokens deny here, before the mutation; thrown past the
                // transaction so the denial audit row survives the rollback.
                val compartmentDecision = evaluate(principal, PolicyOperation.WRITE, prior.patientId.value)
                if (!compartmentDecision.allowed) {
                    throw CompartmentDeniedException(compartmentDecision, prior.patientId.value, encounterId.value)
                }
                val encounter = encounterRepository.transition(scope, encounterId, command)
                    ?: throw EncounterNotFoundForTransition()
                provenanceRecorder.recordUpdated(
                    principal = principal,
                    patientId = encounter.patientId.value,
                    targetResourceType = "ENCOUNTER",
                    targetResourceId = encounter.id.value,
                    newVersion = encounter.version,
                    priorVersion = prior.version,
                    priorState = prior,
                )
                auditEventService.recordResourceAccess(
                    decision = decision,
                    operation = AuditOperation.UPDATE,
                    outcome = AuditOutcome.SUCCESS,
                    patientId = encounter.patientId.value,
                    resourceId = encounter.id.value,
                )
                encounter
            }!!
        } catch (exception: CompartmentDeniedException) {
            auditEventService.recordDeniedAccess(
                exception.decision,
                patientId = exception.patientId,
                resourceId = exception.resourceId,
            )
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to update encounters")
        } catch (exception: EncounterNotFoundForTransition) {
            recordFailedTransition(decision, encounterId)
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Encounter not found")
        } catch (exception: IllegalArgumentException) {
            recordFailedTransition(decision, encounterId)
            throw ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                exception.message ?: "Encounter transition is not allowed",
            )
        } catch (exception: StaleEncounterTransitionException) {
            recordFailedTransition(decision, encounterId)
            throw ResponseStatusException(HttpStatus.CONFLICT, "Encounter was modified concurrently")
        }
    }

    private fun recordFailedTransition(
        decision: PolicyDecision,
        encounterId: EncounterId,
    ) {
        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.UPDATE,
            outcome = AuditOutcome.FAILURE,
            resourceId = encounterId.value,
        )
    }

    private fun authorize(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
        forbiddenMessage: String,
        patientId: java.util.UUID? = null,
        resourceId: java.util.UUID? = null,
    ) = accessAuthorizer.authorize(
        principal = principal,
        resourceType = PolicyResourceType.ENCOUNTER,
        operation = operation,
        forbiddenMessage = forbiddenMessage,
        patientId = patientId,
        resourceId = resourceId,
    )

    private fun evaluate(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
        patientId: java.util.UUID? = null,
    ) = accessAuthorizer.evaluate(
        principal = principal,
        resourceType = PolicyResourceType.ENCOUNTER,
        operation = operation,
        patientId = patientId,
    )


    private class EncounterNotFoundForTransition : RuntimeException()
}
