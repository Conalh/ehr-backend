package dev.ehr.note

import dev.ehr.encounter.EncounterId
import dev.ehr.encounter.EncounterRepository
import dev.ehr.identity.TenantScope
import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientRepository
import dev.ehr.provenance.ProvenanceActivity
import dev.ehr.provenance.ProvenanceRecorder
import dev.ehr.security.AuditEventService
import dev.ehr.security.AccessAuthorizer
import dev.ehr.security.CompartmentDeniedException
import dev.ehr.security.PolicyDecision
import dev.ehr.security.AuditOperation
import dev.ehr.security.AuditOutcome
import dev.ehr.security.PolicyOperation
import dev.ehr.security.PolicyResourceType
import dev.ehr.security.SecurityPrincipal
import dev.ehr.terminology.CodeableConceptId
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException

@Service
class ClinicalNoteService(
    private val accessAuthorizer: AccessAuthorizer,
    private val auditEventService: AuditEventService,
    private val clinicalNoteRepository: ClinicalNoteRepository,
    private val encounterRepository: EncounterRepository,
    private val patientRepository: PatientRepository,
    private val provenanceRecorder: ProvenanceRecorder,
    private val transactionTemplate: TransactionTemplate,
) {
    fun write(
        principal: SecurityPrincipal,
        encounterId: EncounterId,
        typeConceptId: CodeableConceptId,
        title: String,
        contentText: String,
    ): ClinicalNote {
        authorize(
            principal = principal,
            operation = PolicyOperation.WRITE,
            forbiddenMessage = "Not authorized to write clinical notes",
            resourceId = encounterId.value,
        )
        if (title.isBlank() || contentText.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Note title and content must not be blank")
        }

        val scope = tenantScope(principal)
        val encounter = encounterRepository.findById(scope, encounterId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Encounter not found")
        // Re-evaluate with the discovered patient: in enforced organizations
        // a missing treatment relationship denies here.
        val compartmentDecision = authorize(
            principal = principal,
            operation = PolicyOperation.WRITE,
            forbiddenMessage = "Not authorized to write clinical notes",
            patientId = encounter.patientId.value,
            resourceId = encounter.id.value,
        )

        try {
            return transactionTemplate.execute {
                val note = clinicalNoteRepository.create(
                    ClinicalNoteCreateCommand(
                        organizationId = encounter.organizationId,
                        patientId = encounter.patientId,
                        encounterId = encounter.id,
                        typeConceptId = typeConceptId,
                        title = title,
                        contentText = contentText,
                        createdBy = principal.subject.userId,
                    ),
                )
                provenanceRecorder.recordCreated(
                    principal = principal,
                    patientId = note.patientId.value,
                    targetResourceType = "NOTE",
                    targetResourceId = note.id.value,
                )
                auditEventService.recordResourceAccess(
                    decision = compartmentDecision,
                    operation = AuditOperation.CREATE,
                    outcome = AuditOutcome.SUCCESS,
                    patientId = note.patientId.value,
                    resourceId = note.id.value,
                )
                note
            }!!
        } catch (exception: DataIntegrityViolationException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Note type concept is unknown")
        }
    }

    fun amend(
        principal: SecurityPrincipal,
        noteId: ClinicalNoteId,
        title: String?,
        contentText: String?,
        expectedVersion: Int,
    ): ClinicalNote {
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.WRITE,
            forbiddenMessage = "Not authorized to amend clinical notes",
            resourceId = noteId.value,
        )
        if (title == null && contentText == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "An amendment must change the title or content")
        }
        if (title?.isBlank() == true || contentText?.isBlank() == true) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Note title and content must not be blank")
        }

        val scope = tenantScope(principal)
        try {
            return transactionTemplate.execute {
                val prior = clinicalNoteRepository.findById(scope, noteId)
                    ?: throw NoteNotFoundForUpdate()
                // Re-evaluate with the discovered patient: in enforced
                // organizations a missing treatment relationship denies here,
                // before the mutation. Thrown past the transaction so the
                // denial audit row survives the rollback.
                val compartmentDecision = evaluate(principal, PolicyOperation.WRITE, prior.patientId.value)
                if (!compartmentDecision.allowed) {
                    throw CompartmentDeniedException(compartmentDecision, prior.patientId.value, noteId.value)
                }
                val updated = clinicalNoteRepository.amend(
                    tenantScope = scope,
                    noteId = noteId,
                    title = title ?: prior.title,
                    contentText = contentText ?: prior.contentText,
                    expectedVersion = expectedVersion,
                    updatedBy = principal.subject.userId,
                )
                provenanceRecorder.recordUpdated(
                    principal = principal,
                    patientId = prior.patientId.value,
                    targetResourceType = "NOTE",
                    targetResourceId = noteId.value,
                    newVersion = updated.version,
                    priorVersion = prior.version,
                    priorState = prior,
                    activity = ProvenanceActivity.AMENDED,
                )
                auditEventService.recordResourceAccess(
                    decision = compartmentDecision,
                    operation = AuditOperation.UPDATE,
                    outcome = AuditOutcome.SUCCESS,
                    patientId = updated.patientId.value,
                    resourceId = updated.id.value,
                )
                updated
            }!!
        } catch (exception: CompartmentDeniedException) {
            auditEventService.recordDeniedAccess(
                exception.decision,
                patientId = exception.patientId,
                resourceId = exception.resourceId,
            )
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to amend clinical notes")
        } catch (exception: NoteNotFoundForUpdate) {
            recordFailedUpdate(decision, noteId.value)
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Note not found")
        } catch (exception: StaleNoteUpdateException) {
            recordFailedUpdate(decision, noteId.value)
            throw ResponseStatusException(HttpStatus.CONFLICT, "Note was modified concurrently")
        }
    }

    private fun recordFailedUpdate(
        decision: PolicyDecision,
        resourceId: java.util.UUID,
    ) {
        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.UPDATE,
            outcome = AuditOutcome.FAILURE,
            resourceId = resourceId,
        )
    }

    private class NoteNotFoundForUpdate : RuntimeException()

    fun get(
        principal: SecurityPrincipal,
        noteId: ClinicalNoteId,
    ): ClinicalNote {
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read clinical notes",
            resourceId = noteId.value,
        )

        val note = clinicalNoteRepository.findById(tenantScope(principal), noteId)
        if (note == null) {
            auditEventService.recordResourceAccess(
                decision = decision,
                operation = AuditOperation.READ,
                outcome = AuditOutcome.FAILURE,
                resourceId = noteId.value,
            )
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Note not found")
        }

        // Re-evaluate with the discovered patient: in enforced organizations
        // a missing treatment relationship denies here.
        val compartmentDecision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read clinical notes",
            patientId = note.patientId.value,
            resourceId = note.id.value,
        )
        auditEventService.recordResourceAccess(
            decision = compartmentDecision,
            operation = AuditOperation.READ,
            outcome = AuditOutcome.SUCCESS,
            patientId = note.patientId.value,
            resourceId = note.id.value,
        )
        return note
    }

    fun listForPatient(
        principal: SecurityPrincipal,
        patientId: PatientId,
    ): List<ClinicalNote> {
        val decision = authorize(
            principal = principal,
            operation = PolicyOperation.READ,
            forbiddenMessage = "Not authorized to read clinical notes",
            patientId = patientId.value,
        )

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

        val notes = clinicalNoteRepository.findByPatient(scope, patientId)
        auditEventService.recordResourceAccess(
            decision = decision,
            operation = AuditOperation.SEARCH,
            outcome = AuditOutcome.SUCCESS,
            patientId = patientId.value,
        )
        return notes
    }

    private fun authorize(
        principal: SecurityPrincipal,
        operation: PolicyOperation,
        patientId: java.util.UUID? = null,
        resourceId: java.util.UUID? = null,
        forbiddenMessage: String,
    ) = accessAuthorizer.authorize(
        principal = principal,
        resourceType = PolicyResourceType.NOTE,
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
        resourceType = PolicyResourceType.NOTE,
        operation = operation,
        patientId = patientId,
    )

    private fun tenantScope(principal: SecurityPrincipal): TenantScope =
        TenantScope(principal.organization.organizationId)
}
