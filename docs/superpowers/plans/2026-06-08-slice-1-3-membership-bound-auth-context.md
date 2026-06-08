# Slice 1.3 Membership-Bound Auth Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Require an active user membership in the claimed organization before a dev JWT becomes an authenticated request principal.

**Architecture:** Slice 1.3 tightens the Slice 1.2 JWT boundary without becoming a policy engine. Spring Security still verifies the JWT signature, then project code resolves user, organization, active membership, and membership roles from the database into `SecurityPrincipal`.

**Tech Stack:** Kotlin, Spring Boot 3.5.14, Spring Security OAuth2 Resource Server, Spring MVC, JdbcTemplate, PostgreSQL 16, Testcontainers, MockMvc, JUnit 5.

---

## File Structure

- Modify `src/main/kotlin/dev/ehr/security/SecurityContextModels.kt`: add `MembershipContext` and include it in `SecurityPrincipal`.
- Modify `src/main/kotlin/dev/ehr/security/JwtPrincipalAuthenticationConverter.kt`: inject `MembershipRepository`, enforce active membership, and load roles.
- Modify `src/main/kotlin/dev/ehr/security/SecurityConfiguration.kt`: pass `MembershipRepository` into the converter bean.
- Modify `src/main/kotlin/dev/ehr/security/CurrentSecurityController.kt`: expose `membershipId` and `roles` from the principal.
- Modify `src/test/kotlin/dev/ehr/security/SecurityContextModelsTest.kt`: prove principals carry membership context.
- Modify `src/test/kotlin/dev/ehr/security/DevJwtAuthenticationIntegrationTest.kt`: prove membership-bound auth behavior and status rejection.

## Acceptance Criteria

- Valid JWT succeeds only when the active user has an active membership in the claimed active organization.
- `/api/v1/security/whoami` exposes external subject, user ID, organization ID, membership ID, roles, scopes, and optional client ID.
- A user cannot authenticate into an organization where they have no membership.
- Inactive and suspended memberships are rejected.
- Inactive and locked users are rejected.
- Suspended and inactive organizations are rejected.
- Scope parsing remains raw and uninterpreted.
- `/internal/health` remains public.
- `/api/v1/**` and `/fhir/r4/**` remain protected.
- Authentication and `whoami` do not write audit rows.
- No policy engine, audit service, clinical/patient tables, FHIR resources, SMART launch, Keycloak, refresh tokens, consent/break-glass, RLS, or patient-compartment authorization is introduced.

## Task 1: Write Failing Membership-Bound Auth Tests

- [ ] Update `DevJwtAuthenticationIntegrationTest` to create memberships and roles for successful JWT auth.
- [ ] Add failure cases for no membership, inactive membership, suspended membership, inactive user, locked user, suspended organization, and inactive organization.
- [ ] Update `SecurityContextModelsTest` to expect `SecurityPrincipal` to carry `MembershipContext`.
- [ ] Run targeted tests and confirm they fail because membership context is not yet part of the principal/converter.

## Task 2: Implement Membership Context Models

- [ ] Add `MembershipContext(membershipId, roles)` in `SecurityContextModels.kt`.
- [ ] Add the membership context field to `SecurityPrincipal`.
- [ ] Update `CurrentSecurityController` response with `membershipId` and role names.
- [ ] Run model tests and confirm they pass.

## Task 3: Enforce Active Membership In JWT Conversion

- [ ] Inject `MembershipRepository` into `JwtPrincipalAuthenticationConverter`.
- [ ] Resolve membership by organization ID and user ID after user and organization are active.
- [ ] Reject missing, inactive, or suspended membership as invalid tokens.
- [ ] Load roles from the repository and store them in `MembershipContext`.
- [ ] Keep scope parsing raw and uninterpreted.

## Task 4: Verify And Commit

- [ ] Run targeted auth and model tests.
- [ ] Run `.\gradlew.bat test --rerun-tasks`.
- [ ] Audit that no deferred policy, audit service, clinical, FHIR, SMART, Keycloak, refresh token, consent/break-glass, RLS, or patient compartment behavior was introduced.
- [ ] Commit the Slice 1.3 plan, tests, and implementation.

## Self-Review Checklist

- JWT org and role claims are not trusted for membership or role context.
- Membership context is database-backed.
- Only `MembershipStatus.ACTIVE` is accepted.
- Existing `/api/v1/**` and `/fhir/r4/**` protection stays intact.
- No audit rows are written yet.
