package dev.ehr.provenance

import dev.ehr.identity.OAuthClientId
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import java.time.Instant
import java.util.UUID

enum class ProvenanceActivity(val dbValue: String) {
    CREATED("created"),
    UPDATED("updated"),
    CORRECTED("corrected"),
    AMENDED("amended"),
    ADDENDED("addended"),
    ENTERED_IN_ERROR("entered-in-error"),
    ;

    companion object {
        fun fromDb(dbValue: String): ProvenanceActivity =
            entries.first { it.dbValue == dbValue }
    }
}

enum class ProvenanceSourceType(val dbValue: String) {
    CLINICIAN_AUTHORED("clinician-authored"),
    STAFF_RECORDED("staff-recorded"),
    SYSTEM_IMPORTED("system-imported"),
    TRANSFORMED("transformed"),
    SYNTHETIC_GENERATED("synthetic-generated"),
    CORRECTED("corrected"),
    AMENDED("amended"),
    ADDENDED("addended"),
    ;

    companion object {
        fun fromDb(dbValue: String): ProvenanceSourceType =
            entries.first { it.dbValue == dbValue }
    }
}

data class ProvenanceEventCommand(
    val organizationId: OrganizationId,
    val patientId: UUID,
    val targetResourceType: String,
    val targetResourceId: UUID,
    val targetVersion: Int,
    val activity: ProvenanceActivity,
    val sourceType: ProvenanceSourceType,
    val agentUserId: UserId? = null,
    val agentClientId: OAuthClientId? = null,
    val sourceReference: String? = null,
    val priorResourceVersion: Int? = null,
    val syntheticGenerationRunId: UUID? = null,
)

data class ProvenanceEvent(
    val id: UUID,
    val organizationId: OrganizationId,
    val patientId: UUID,
    val targetResourceType: String,
    val targetResourceId: UUID,
    val targetVersion: Int,
    val activity: ProvenanceActivity,
    val agentUserId: UserId?,
    val agentClientId: OAuthClientId?,
    val recordedAt: Instant,
    val sourceType: ProvenanceSourceType,
    val sourceReference: String?,
    val priorResourceVersion: Int?,
    val syntheticGenerationRunId: UUID?,
)

data class ResourceRevisionCommand(
    val organizationId: OrganizationId,
    val patientId: UUID,
    val resourceType: String,
    val resourceId: UUID,
    val version: Int,
    val snapshotJson: String,
    val recordedBy: UserId? = null,
)

data class ResourceRevision(
    val id: UUID,
    val organizationId: OrganizationId,
    val patientId: UUID,
    val resourceType: String,
    val resourceId: UUID,
    val version: Int,
    val snapshotJson: String,
    val recordedAt: Instant,
    val recordedBy: UserId?,
)
