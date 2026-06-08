# Slice 1.7 Tenant Guard Contracts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the Slice 1 preclinical guardrail by adding tenant-guard contracts and cross-organization isolation tests before patient registry work begins.

**Architecture:** Slice 1.7 hardens the existing identity/auth/policy/audit spine without adding clinical records. It makes organization-bound repository reads the preferred contract for tenant-scoped membership data, migrates the JWT principal path to organization-bound role lookup, and adds tests that deliberately attempt wrong-organization reads. PostgreSQL Row-Level Security remains deferred until clinical tables exist, but this slice defines the application-level tenant contract that later RLS policies should reinforce.

**Standards Notes:** US Core 6.1 security guidance expects limited authorized access and audit measures for patient-specific transactions. FHIR R4 `AuditEvent` explicitly includes access-control decisions as security-relevant events. NIST SP 800-66 Rev. 2 supports evidence-producing access-control and audit practices for healthcare systems, but this project remains synthetic-only and does not claim HIPAA compliance. PostgreSQL RLS is policy/table-level and default-deny once enabled, so it is intentionally deferred until the first tenant-scoped clinical tables exist.

**Tech Stack:** Kotlin, Spring Boot 3.5.14, Spring Security OAuth2 Resource Server, Spring MVC, Spring JDBC/JdbcTemplate, PostgreSQL 16, Testcontainers, MockMvc, JUnit 5.

---

## File Structure

- Create `src/main/kotlin/dev/ehr/identity/TenantScope.kt`: small organization-bound tenant value type.
- Modify `src/main/kotlin/dev/ehr/identity/MembershipRepository.kt`: add organization-bound membership and role reads.
- Modify `src/main/kotlin/dev/ehr/security/JwtPrincipalAuthenticationConverter.kt`: use organization-bound role lookup.
- Modify `src/test/kotlin/dev/ehr/identity/IdentityRepositoryIntegrationTest.kt`: cross-org repository guard coverage.
- Modify `src/test/kotlin/dev/ehr/security/DevJwtAuthenticationIntegrationTest.kt`: preserve protected/public route behavior and auth context.
- Modify `src/test/kotlin/dev/ehr/security/PolicyEvaluatorTest.kt`: reinforce organization mismatch denial.
- Modify `src/test/kotlin/dev/ehr/security/PolicyDecisionEndpointIntegrationTest.kt`: verify an authenticated organization mismatch policy decision is audited.
- Add or extend schema assertions proving tenant-scoped identity columns have expected nullability.

## Acceptance Criteria

- A small tenant guard model exists, wrapping `OrganizationId` as the explicit tenant boundary.
- `MembershipRepository` supports organization-bound membership lookup:
  - correct organization + membership ID returns the membership;
  - wrong organization + membership ID returns null.
- `MembershipRepository` supports organization-bound role lookup:
  - correct organization + membership ID returns roles;
  - wrong organization + membership ID returns an empty list.
- Current security/auth code uses organization-bound role lookup where organization context exists.
- Existing global membership helper methods are not removed unless that produces low-risk cleanup; if they remain, the plan documents that they are legacy/internal and not the preferred tenant-scoped contract.
- Cross-org fixture tests prove organization-aware repository methods do not leak rows across organizations.
- Schema tests assert:
  - `memberships.organization_id` is `NOT NULL`;
  - `oauth_clients.organization_id` is nullable by design because system/global clients are already represented.
- `PolicyEvaluator` continues to deny organization mismatch with `ORGANIZATION_MISMATCH`.
- An authenticated organization mismatch decision can be audited as `AUTHORIZATION_DENIED` + `DENIED`.
- Existing whoami/auth behavior remains green.
- Existing audit append-only behavior remains green.
- Existing terminology tests remain green.
- `/internal/health` remains public.
- `/api/v1/**` remains protected.
- `/fhir/r4/**` remains protected.

## Intentional Deferrals

- No patient tables.
- No patient identifiers.
- No `PatientId`.
- No patient compartment authorization.
- No clinical resources.
- No FHIR Patient read/search.
- No FHIR Bundle or OperationOutcome work.
- No FHIR AuditEvent mapping.
- No HAPI validator integration.
- No SMART launch flow.
- No Keycloak.
- No refresh tokens.
- No consent or break-glass.
- No PostgreSQL RLS policies.
- No audit query API.
- No broad request audit interceptor.
- No frontend.

## Task 1: Write Failing Tenant Guard Tests

- [ ] Add repository tests for organization-bound membership lookup success and wrong-org null result.
- [ ] Add repository tests for organization-bound role lookup success and wrong-org empty result.
- [ ] Add or extend tests proving `findByOrganizationId` only returns rows for the requested organization.
- [ ] Add schema nullability assertions for `memberships.organization_id` and `oauth_clients.organization_id`.
- [ ] Add policy evaluator coverage for organization mismatch evidence.
- [ ] Add endpoint/audit coverage for an authenticated organization mismatch policy decision.
- [ ] Run targeted tests and confirm failures are caused by missing tenant guard model/methods or missing test-only policy endpoint support.

## Task 2: Implement Tenant Guard Model And Repository Contracts

- [ ] Add `TenantScope`.
- [ ] Add `MembershipRepository.findById(TenantScope, MembershipId)` or equivalent.
- [ ] Add `MembershipRepository.findRoles(TenantScope, MembershipId)` or equivalent.
- [ ] Implement role lookup through a join to `memberships` constrained by `organization_id`.
- [ ] Keep SQL explicit and small.

## Task 3: Migrate Security Path

- [ ] Update `JwtPrincipalAuthenticationConverter` to load membership roles with the resolved organization boundary.
- [ ] Keep membership resolution active-status based.
- [ ] Do not trust JWT role claims.
- [ ] Do not add audit rows for authentication or whoami.

## Task 4: Verify

- [ ] Run focused tests:
  - `IdentityRepositoryIntegrationTest`
  - `DevJwtAuthenticationIntegrationTest`
  - `PolicyEvaluatorTest`
  - `PolicyDecisionEndpointIntegrationTest`
  - `AuditEventRepositoryIntegrationTest`
- [ ] Run `.\gradlew.bat test --rerun-tasks`.
- [ ] Search for deferred patient, clinical, FHIR, SMART, Keycloak, refresh-token, consent, break-glass, RLS, patient-compartment, audit-query, broad-interceptor, or frontend work.
- [ ] Search audit code for raw bearer/JWT/header/query/FHIR payload storage.

## Task 5: Commit

- [ ] Commit plan artifact as `docs: add Slice 1.7 tenant guard plan`.
- [ ] Commit implementation and tests as `feat: add tenant guard contracts`.

## Self-Review Checklist

- Every new tenant-scoped membership read accepts an organization boundary.
- Wrong-organization membership and role lookups fail closed.
- Tests prove positive and negative paths.
- The auth principal still derives membership/roles from the database.
- The audit service still records denied access without unsafe request data.
- RLS remains deliberately deferred and documented.
- Slice 2 can start patient registry work against a clearer tenant isolation pattern.
