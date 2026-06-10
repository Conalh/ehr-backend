package dev.ehr.provenance

import com.fasterxml.jackson.databind.ObjectMapper
import dev.ehr.identity.MembershipRole
import dev.ehr.security.SecurityPrincipal
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Appends provenance (and prior-state revisions for mutations) inside the caller's
 * transaction. Clinical writes must go through this so no code path mutates clinical
 * data without provenance.
 */
@Service
class ProvenanceRecorder(
    private val provenanceRepository: ProvenanceRepository,
    private val resourceRevisionRepository: ResourceRevisionRepository,
    private val objectMapper: ObjectMapper,
) {
    fun recordCreated(
        principal: SecurityPrincipal,
        patientId: UUID,
        targetResourceType: String,
        targetResourceId: UUID,
    ): ProvenanceEvent =
        provenanceRepository.append(
            ProvenanceEventCommand(
                organizationId = principal.organization.organizationId,
                patientId = patientId,
                targetResourceType = targetResourceType,
                targetResourceId = targetResourceId,
                targetVersion = 1,
                activity = ProvenanceActivity.CREATED,
                sourceType = sourceTypeFor(principal),
                agentUserId = principal.subject.userId,
                agentClientId = principal.subject.clientId,
            ),
        )

    fun recordUpdated(
        principal: SecurityPrincipal,
        patientId: UUID,
        targetResourceType: String,
        targetResourceId: UUID,
        newVersion: Int,
        priorVersion: Int,
        priorState: Any,
        activity: ProvenanceActivity = ProvenanceActivity.UPDATED,
    ): ProvenanceEvent {
        resourceRevisionRepository.append(
            ResourceRevisionCommand(
                organizationId = principal.organization.organizationId,
                patientId = patientId,
                resourceType = targetResourceType,
                resourceId = targetResourceId,
                version = priorVersion,
                snapshotJson = objectMapper.writeValueAsString(priorState),
                recordedBy = principal.subject.userId,
            ),
        )
        return provenanceRepository.append(
            ProvenanceEventCommand(
                organizationId = principal.organization.organizationId,
                patientId = patientId,
                targetResourceType = targetResourceType,
                targetResourceId = targetResourceId,
                targetVersion = newVersion,
                activity = activity,
                sourceType = sourceTypeFor(principal),
                agentUserId = principal.subject.userId,
                agentClientId = principal.subject.clientId,
                priorResourceVersion = priorVersion,
            ),
        )
    }

    private fun sourceTypeFor(principal: SecurityPrincipal): ProvenanceSourceType =
        when {
            MembershipRole.CLINICIAN in principal.membership.roles -> ProvenanceSourceType.CLINICIAN_AUTHORED
            MembershipRole.STAFF in principal.membership.roles -> ProvenanceSourceType.STAFF_RECORDED
            else -> ProvenanceSourceType.SYSTEM_IMPORTED
        }
}
