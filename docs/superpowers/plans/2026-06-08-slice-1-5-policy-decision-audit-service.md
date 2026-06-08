# Slice 1.5 Policy Decision Audit Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist audit rows for policy decisions made by the protected placeholder policy endpoint.

**Architecture:** Slice 1.5 adds the first explicit audit writer over the existing append-only `audit_events` schema. It records authorization outcomes from `/api/v1/security/policy-check` only; clinical audit coverage, interceptors, FHIR AuditEvent mapping, OAuth client persistence, and audit query APIs remain deferred.

**Tech Stack:** Kotlin, Spring Boot 3.5.14, Spring MVC, Spring Security OAuth2 Resource Server, Spring JDBC/JdbcTemplate, PostgreSQL 16, Testcontainers, MockMvc, JUnit 5.

---

## File Structure

- Create `src/main/kotlin/dev/ehr/security/AuditModels.kt`: audit operation, outcome, and insert command types.
- Create `src/main/kotlin/dev/ehr/security/AuditEventRepository.kt`: appends rows to `audit_events`.
- Create `src/main/kotlin/dev/ehr/security/AuditEventService.kt`: maps `PolicyDecision` to audit commands.
- Modify `src/main/kotlin/dev/ehr/security/PolicyDecisionController.kt`: call `AuditEventService` after evaluating policy-check.
- Create `src/test/kotlin/dev/ehr/security/AuditEventRepositoryIntegrationTest.kt`: repository insert and append-only behavior.
- Modify `src/test/kotlin/dev/ehr/security/PolicyDecisionEndpointIntegrationTest.kt`: allowed/denied policy-check now writes one audit row.

## Acceptance Criteria

- `AuditEventRepository` inserts one audit row into `audit_events` with expected fields.
- Existing append-only database behavior still holds.
- Allowed `ORGANIZATION` + `READ` policy decision writes:
  - `operation = READ`
  - `outcome = SUCCESS`
  - `resource_type = ORGANIZATION`
  - organization ID, subject user ID, policy version, policy reason code.
- Denied policy decision writes:
  - `operation = AUTHORIZATION_DENIED`
  - `outcome = DENIED`
  - resource type from the policy decision.
  - organization ID, subject user ID, policy version, policy reason code.
- Audit row captures correlation ID from `X-Correlation-Id` when present.
- Audit metadata remains a safe JSON object and does not store raw bearer tokens, raw JWT claims, raw request headers, raw query strings, raw FHIR payloads, or PHI.
- Audit `client_id` remains null until a persisted OAuth client lookup exists.
- Unauthenticated policy-check still returns 401 and writes no audit row in this slice.
- Existing whoami/auth behavior remains green.
- `/internal/health` remains public.
- `/api/v1/**` and `/fhir/r4/**` remain protected.
- No clinical/patient tables, FHIR resources, FHIR AuditEvent mapping, SMART launch or SMART scope semantics, Keycloak, refresh tokens, consent/break-glass, RLS, patient-compartment authorization, audit query API, broad audit interceptor, OAuth client persistence, or frontend is introduced.

## Task 1: Write Failing Audit Tests

- [ ] Add `AuditEventRepositoryIntegrationTest` for direct insert of a safe audit command and append-only behavior.
- [ ] Update `PolicyDecisionEndpointIntegrationTest` so allowed policy-check expects exactly one `READ` + `SUCCESS` audit row.
- [ ] Update `PolicyDecisionEndpointIntegrationTest` so denied policy-check expects exactly one `AUTHORIZATION_DENIED` + `DENIED` audit row.
- [ ] Add correlation ID assertion for policy-check audit rows.
- [ ] Keep unauthenticated policy-check expecting 401 and zero audit rows.
- [ ] Run targeted tests and confirm failures are due to missing audit model/repository/service or missing policy-check audit integration.

## Task 2: Implement Audit Models And Repository

- [ ] Add `AuditOperation` values matching schema: `READ`, `AUTHORIZATION_DENIED`.
- [ ] Add `AuditOutcome` values matching schema: `SUCCESS`, `DENIED`.
- [ ] Add `AuditEventCommand`.
- [ ] Add `AuditEventRecord` for test-friendly reads.
- [ ] Add `AuditEventRepository.append(command)` that inserts into `audit_events` and returns the inserted row.

## Task 3: Implement Policy Decision Audit Service

- [ ] Add `AuditEventService.recordPolicyDecision(decision)`.
- [ ] Map allowed decisions to `READ` + `SUCCESS`.
- [ ] Map denied decisions to `AUTHORIZATION_DENIED` + `DENIED`.
- [ ] Copy organization ID, subject user ID, resource type, policy version, and reason code from the decision.
- [ ] Read correlation ID from `MDC["correlationId"]`.
- [ ] Leave `client_id` null.
- [ ] Store only safe metadata.

## Task 4: Integrate Policy-Check Endpoint

- [ ] Inject `AuditEventService` into `PolicyDecisionController`.
- [ ] Record the policy decision before returning the response.
- [ ] Do not audit unauthenticated requests in this slice.

## Task 5: Verify And Commit

- [ ] Run targeted audit, policy endpoint, and auth tests.
- [ ] Run `.\gradlew.bat test --rerun-tasks`.
- [ ] Audit that no deferred clinical, FHIR, SMART, Keycloak, refresh token, consent/break-glass, RLS, patient-compartment, audit query API, broad interceptor, OAuth-client persistence, or frontend behavior was introduced.
- [ ] Commit the Slice 1.5 plan, tests, and implementation.

## Self-Review Checklist

- Audit writes are explicit at the service/endpoint boundary.
- No broad request interceptor is added.
- No raw tokens, claims, headers, query strings, FHIR payloads, or PHI are stored.
- `client_id` remains null until backed by a persisted `oauth_clients` row.
- Policy-check writes exactly one audit row per authenticated request.
