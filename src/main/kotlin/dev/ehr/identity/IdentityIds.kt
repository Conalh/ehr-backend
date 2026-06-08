package dev.ehr.identity

import java.util.UUID

@JvmInline
value class OrganizationId(val value: UUID)

@JvmInline
value class UserId(val value: UUID)

@JvmInline
value class PractitionerId(val value: UUID)

@JvmInline
value class MembershipId(val value: UUID)

@JvmInline
value class OAuthClientId(val value: UUID)
