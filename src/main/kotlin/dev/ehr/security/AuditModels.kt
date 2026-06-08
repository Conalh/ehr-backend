package dev.ehr.security

import dev.ehr.identity.OAuthClientId
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import java.time.Instant
import java.util.UUID

enum class AuditOperation(val dbValue: String) {
    READ("READ"),
    AUTHORIZATION_DENIED("AUTHORIZATION_DENIED"),
}

enum class AuditOutcome(val dbValue: String) {
    SUCCESS("SUCCESS"),
    DENIED("DENIED"),
}

data class AuditEventCommand(
    val organizationId: OrganizationId?,
    val subjectUserId: UserId?,
    val resourceType: String,
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
    val resourceType: String,
    val operation: AuditOperation,
    val outcome: AuditOutcome,
    val policyVersion: String?,
    val policyReasonCode: String?,
    val correlationId: String?,
    val metadata: String,
)
