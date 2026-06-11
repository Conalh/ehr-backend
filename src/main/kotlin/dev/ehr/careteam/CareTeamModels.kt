package dev.ehr.careteam

import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import dev.ehr.patient.PatientId
import java.time.Instant
import java.util.UUID

@JvmInline
value class CareTeamMembershipId(val value: UUID)

enum class CareTeamRole(val dbValue: String) {
    ATTENDING("attending"),
    COVERING("covering"),
    CARE_TEAM("care-team"),
    ;

    companion object {
        fun fromDb(dbValue: String): CareTeamRole =
            entries.first { it.dbValue == dbValue }
    }
}

enum class CareTeamMembershipOrigin(val dbValue: String) {
    EXPLICIT("explicit"),
    ENCOUNTER_DERIVED("encounter-derived"),
    ;

    companion object {
        fun fromDb(dbValue: String): CareTeamMembershipOrigin =
            entries.first { it.dbValue == dbValue }
    }
}

data class CareTeamMembership(
    val id: CareTeamMembershipId,
    val organizationId: OrganizationId,
    val patientId: PatientId,
    val userId: UserId,
    val role: CareTeamRole,
    val origin: CareTeamMembershipOrigin,
    val periodStart: Instant,
    val periodEnd: Instant?,
    val createdAt: Instant,
    val createdBy: UserId?,
)
