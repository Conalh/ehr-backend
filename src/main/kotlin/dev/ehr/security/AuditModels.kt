package dev.ehr.security

import dev.ehr.identity.OAuthClientId
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import java.time.Instant
import java.util.UUID

enum class AuditOperation(val dbValue: String) {
    READ("READ"),
    SEARCH("SEARCH"),
    CREATE("CREATE"),
    AUTHORIZATION_DENIED("AUTHORIZATION_DENIED"),
}

enum class AuditOutcome(val dbValue: String) {
    SUCCESS("SUCCESS"),
    DENIED("DENIED"),
    FAILURE("FAILURE"),
}

data class AuditEventCommand(
    val organizationId: OrganizationId?,
    val subjectUserId: UserId?,
    val patientId: UUID? = null,
    val resourceType: String,
    val resourceId: UUID? = null,
    val operation: AuditOperation,
    val outcome: AuditOutcome,
    val policyVersion: String?,
    val policyReasonCode: String?,
    val correlationId: String?,
    val metadata: String = "{}",
)

data class AuditEventRecord(
    val id: UUID,
    val occurredAt: Instant,
    val organizationId: OrganizationId?,
    val subjectUserId: UserId?,
    val clientId: OAuthClientId?,
    val patientId: UUID?,
    val resourceType: String,
    val resourceId: UUID?,
    val operation: AuditOperation,
    val outcome: AuditOutcome,
    val policyVersion: String?,
    val policyReasonCode: String?,
    val correlationId: String?,
    val metadata: String,
)
