package dev.ehr.identity

import java.time.Instant

data class Organization(
    val id: OrganizationId,
    val slug: String,
    val displayName: String,
    val status: OrganizationStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class User(
    val id: UserId,
    val externalSubject: String,
    val email: String,
    val displayName: String,
    val status: UserStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class Practitioner(
    val id: PractitionerId,
    val userId: UserId,
    val npi: String?,
    val displayName: String,
    val status: PractitionerStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class Membership(
    val id: MembershipId,
    val organizationId: OrganizationId,
    val userId: UserId,
    val practitionerId: PractitionerId?,
    val status: MembershipStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class OAuthClient(
    val id: OAuthClientId,
    val organizationId: OrganizationId?,
    val clientIdentifier: String,
    val displayName: String,
    val status: OAuthClientStatus,
    val clientType: OAuthClientType,
    // Argon2id hash; the plain secret exists only in the registration response.
    val secretHash: String?,
    val grantedScopes: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)
