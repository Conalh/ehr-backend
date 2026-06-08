# Slice 1.2 Dev JWT Auth Boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a dev/test JWT authentication boundary that resolves signed local JWTs into the existing identity/security model.

**Architecture:** This slice uses Spring Security resource-server primitives and a local HMAC dev key. JWTs are decoded by Spring Security, converted into `SecurityPrincipal`, and used only to prove authentication and organization context. Authorization policy, audit writes, SMART launch, Keycloak, patients, and FHIR resources remain out of scope.

**Tech Stack:** Kotlin, Spring Boot 3.5.14, Spring Security, OAuth2 Resource Server, Nimbus JWT support, Spring MVC, MockMvc, JdbcTemplate, PostgreSQL 16, Testcontainers.

---

## File Structure

- Modify `build.gradle.kts`: add Spring Security resource server dependencies and security test support.
- Add `src/main/kotlin/dev/ehr/security/JwtClaimNames.kt`: local claim-name constants.
- Add `src/main/kotlin/dev/ehr/security/JwtPrincipalAuthenticationConverter.kt`: maps Spring `Jwt` to `SecurityPrincipal`.
- Add `src/main/kotlin/dev/ehr/security/SecurityConfiguration.kt`: public health, protected `/api/v1/**` and `/fhir/r4/**`, HMAC dev JWT decoder.
- Add `src/main/kotlin/dev/ehr/security/CurrentSecurityController.kt`: placeholder `/api/v1/security/whoami` endpoint for auth-boundary verification.
- Add `src/test/kotlin/dev/ehr/testsupport/DevJwtFactory.kt`: test-only signed JWT helper.
- Add `src/test/kotlin/dev/ehr/security/DevJwtAuthenticationIntegrationTest.kt`: end-to-end auth boundary tests.

## Acceptance Criteria

- `/internal/health` remains public.
- `/api/v1/security/whoami` rejects unauthenticated requests.
- A valid signed dev JWT authenticates and exposes subject, user ID, organization ID, scopes, and optional client ID.
- Unknown user subjects are rejected.
- Unknown organization IDs/slugs are rejected.
- Scope parsing preserves raw scope strings without SMART interpretation.
- No audit row is written by authentication or the placeholder protected endpoint.
- `/api/v1/**` and `/fhir/r4/**` require authentication by default.
- No policy engine, audit service, clinical/patient tables, FHIR resources, SMART launch, Keycloak, refresh tokens, consent/break-glass, or RLS behavior is introduced.

## Task 1: Write Failing Auth Boundary Tests

- [ ] Add `DevJwtAuthenticationIntegrationTest` covering public health, unauthenticated rejection, valid JWT, unknown subject, unknown organization, scope parsing, and no audit writes.
- [ ] Add `DevJwtFactory` as a test helper for signed HMAC JWTs.
- [ ] Run targeted tests and confirm they fail because Spring Security/JWT classes and security configuration do not exist.

## Task 2: Add Spring Security Dependencies

- [ ] Add `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`, and `spring-security-test`.
- [ ] Run targeted tests and confirm failures now point to missing project security classes.

## Task 3: Implement JWT Principal Conversion

- [ ] Add claim-name constants.
- [ ] Add `JwtPrincipalAuthenticationConverter` that resolves users by `sub`, organizations by `org_id` or `org_slug`, parses scopes, parses optional UUID `client_id`, and returns a `SecurityPrincipal`.
- [ ] Reject missing or unknown subject/organization with OAuth2 invalid-token errors.

## Task 4: Implement Security Configuration And Placeholder Endpoint

- [ ] Add HMAC `JwtDecoder` from local `ehr.security.dev-jwt-secret`.
- [ ] Permit `/internal/health` and `/actuator/health`.
- [ ] Require authentication for `/api/v1/**` and `/fhir/r4/**`.
- [ ] Add `/api/v1/security/whoami` to expose the resolved principal for tests.

## Task 5: Verify And Commit

- [ ] Run targeted auth tests.
- [ ] Run `.\gradlew.bat test --rerun-tasks`.
- [ ] Commit the plan, dependency change, security implementation, helper, and tests.

## Self-Review Checklist

- Spring Security handles token decoding and signature verification.
- Project code converts a verified `Jwt`; it does not parse bearer tokens manually.
- SecurityScope still preserves raw tokens only.
- No audit service or policy decisions are introduced.
- No clinical or FHIR resources are introduced.
