# Slice 2.1 Patient Internal API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose the patient registry through the first internal clinical HTTP endpoints: policy-gated, tenant-scoped, fully audited patient create, read, and identifier search under `/api/v1/patients`.

**Architecture:** Slice 2.1 puts the existing identity/policy/audit spine in front of the Slice 2.0 patient repository. It extends `PolicyEvaluator` with a `PATIENT` resource type and role/scope rules, extends the audit path to record patient and resource IDs (the `audit_events` columns already exist, so no migration is needed), and adds a transactional `PatientService` so clinical writes commit together with their audit rows. FHIR Patient mapping remains a later slice; this is the internal product API only.

**Standards Notes:** The design spec requires every patient read/search/write to be audited, including denied attempts. FHIR R4 `AuditEvent` and US Core security guidance treat patient-record access as a security-relevant event, which is why the audit row carries `patient_id` and `resource_id` for later patient-compartment audit queries. Role rules follow the V1 role model: `CLINICIAN` reads and writes patient demographics, `STAFF` reads demographics, `ORG_ADMIN`/`SYSTEM_ADMIN` have no routine chart or registry access by default.

**Tech Stack:** Kotlin, Spring Boot 3.5.14, Spring Security OAuth2 Resource Server, Spring MVC, Spring JDBC/JdbcTemplate, Jakarta Validation, PostgreSQL 16, Testcontainers, MockMvc, JUnit 5.

---

## File Structure

- Create `src/main/kotlin/dev/ehr/patient/PatientController.kt`: internal patient endpoints and request/response DTOs.
- Create `src/main/kotlin/dev/ehr/patient/PatientService.kt`: policy check + audit + repository coordination, transactional create.
- Modify `src/main/kotlin/dev/ehr/security/PolicyModels.kt`: add `PATIENT` resource type.
- Modify `src/main/kotlin/dev/ehr/security/PolicyEvaluator.kt`: patient read/write role and scope rules, bump policy version.
- Modify `src/main/kotlin/dev/ehr/security/AuditModels.kt`: add `CREATE`/`SEARCH` operations, `FAILURE` outcome, `patientId`/`resourceId` on command and record.
- Modify `src/main/kotlin/dev/ehr/security/AuditEventRepository.kt`: persist and return `patient_id`/`resource_id`.
- Modify `src/main/kotlin/dev/ehr/security/AuditEventService.kt`: record patient access decisions with resource context.
- Create `src/test/kotlin/dev/ehr/patient/PatientApiIntegrationTest.kt`: endpoint, tenancy, and audit coverage.
- Modify `src/test/kotlin/dev/ehr/security/PolicyEvaluatorTest.kt`: patient policy matrix.
- Modify `src/test/kotlin/dev/ehr/security/AuditEventRepositoryIntegrationTest.kt`: patient/resource ID persistence coverage.
- Modify `src/test/kotlin/dev/ehr/security/PolicyDecisionEndpointIntegrationTest.kt` and `PolicyModelsTest.kt`: policy version literal updates only.

## Acceptance Criteria

- `POST /api/v1/patients` creates a patient (optional identifiers) in the caller's organization and returns `201` with the created patient and identifiers.
- `GET /api/v1/patients/{patientId}` returns the patient with identifiers, or `404` when absent or in another organization.
- `GET /api/v1/patients?identifierSystem=...&identifierValue=...` returns exact-match results inside the caller's organization only (empty list when no match).
- Policy rules:
  - `PATIENT` + `READ` allowed for `CLINICIAN` and `STAFF` with a compatible read scope (`user/Patient.read`, `user/*.read`, `system/Patient.read`, `system/*.read`);
  - `PATIENT` + `WRITE` allowed for `CLINICIAN` with a compatible write scope (`user/Patient.write`, `user/*.write`, `system/Patient.write`, `system/*.write`);
  - all other roles are denied with `INSUFFICIENT_ROLE`; missing scopes deny with `INSUFFICIENT_SCOPE`;
  - organization mismatch continues to deny with `ORGANIZATION_MISMATCH`;
  - `POLICY_VERSION` becomes `policy-spine-v2` and existing literal assertions are updated deliberately.
- Audit rules:
  - successful create audits `CREATE` + `SUCCESS` with `patient_id` and `resource_id`, in the same transaction as the insert;
  - successful read audits `READ` + `SUCCESS` with `patient_id` and `resource_id`;
  - not-found read audits `READ` + `FAILURE` with `resource_id` set to the requested UUID and `patient_id` null (existence unconfirmed, so the patient compartment is not polluted);
  - identifier search audits `SEARCH` with `patient_id` set when a match exists; the identifier system/value are not stored in the audit row;
  - denied access audits `AUTHORIZATION_DENIED` + `DENIED` and the endpoint returns `403`;
  - unauthenticated requests return `401` with no audit row (consistent with the existing policy endpoint).
- Validation errors (blank names, malformed body, missing search params, invalid identifier period) return `400` without writing patient rows.
- Audit rows never contain names, birth dates, identifier values, raw headers, or request bodies.
- Existing identity/security/audit/terminology/patient repository tests remain green apart from the documented policy-version literal updates.

## Intentional Deferrals

- No FHIR Patient mapping, FHIR search, Bundle, or OperationOutcome.
- No patient update or status-transition endpoints.
- No patient merge.
- No paginated or demographic search; identifier exact match only.
- No patient-compartment or care-team assignment authorization beyond organization + role + scope.
- No provenance service or revision history (Slice 4).
- No RLS policies.
- No SMART launch, Keycloak, or refresh tokens.
- No consent or break-glass.
- No audit query API.
- No frontend.

## Task 1: Write Failing Policy And Audit Tests

- [ ] Extend `PolicyEvaluatorTest` with the patient matrix: clinician read/write allowed, staff read allowed, staff write denied, org-admin read denied, scope-incompatible denied, wrong-organization denied.
- [ ] Extend `AuditEventRepositoryIntegrationTest` to append and read back an event carrying `patient_id` and `resource_id`, and a `CREATE`/`SEARCH` operation and `FAILURE` outcome.
- [ ] Run targeted tests and confirm failures are due to missing `PATIENT` resource type, missing audit fields, or missing enum values.

## Task 2: Write Failing Patient API Tests

- [ ] Add `PatientApiIntegrationTest` covering: unauthenticated `401` without audit; clinician create `201` with DB row, identifier rows, and `CREATE` audit; staff create `403` with denied audit; clinician/staff read `200` with `READ` audit; cross-organization read `404` with `READ`+`FAILURE` audit; identifier search hit and empty-result `200` with `SEARCH` audit; insufficient scope `403` with denied audit; validation `400` without patient row.
- [ ] Reuse the existing dev JWT + fixture helpers (`DevJwtTestConfiguration`, `DevJwtFactory`, repositories) for two-organization setups.
- [ ] Run targeted tests and confirm failures are due to missing endpoints/service.

## Task 3: Extend Policy And Audit Spine

- [ ] Add `PolicyResourceType.PATIENT` and patient role/scope rules in `PolicyEvaluator`; keep evidence fields (roleBasis/scopeBasis/reasonCode) populated the same way as organization decisions.
- [ ] Bump `POLICY_VERSION` to `policy-spine-v2` and update literal assertions.
- [ ] Add `AuditOperation.CREATE`, `AuditOperation.SEARCH`, `AuditOutcome.FAILURE`.
- [ ] Add `patientId`/`resourceId` to `AuditEventCommand`/`AuditEventRecord` and persist them in `AuditEventRepository`.
- [ ] Add an `AuditEventService` method that records a patient access outcome from a `PolicyDecision` plus resource context.

## Task 4: Implement Patient Service And Controller

- [ ] Add `PatientService` taking the `SecurityPrincipal`, evaluating policy, recording audit, and delegating to `PatientRepository` with a `TenantScope` derived from the principal's organization.
- [ ] Make create transactional so the patient insert, identifier inserts, and audit append commit together.
- [ ] Add `PatientController` with request/response DTOs, Jakarta Validation, `201`/`200`/`400`/`403`/`404` semantics, and no clinical payloads in logs or errors.
- [ ] Keep DTO IDs as strings and dates as ISO-8601, consistent with existing controllers.

## Task 5: Verify And Commit

- [ ] Run focused patient, policy, and audit tests.
- [ ] Run `.\gradlew.bat test --rerun-tasks`.
- [ ] Search for deferred FHIR, OperationOutcome, update/merge, provenance, revision, RLS, SMART, Keycloak, refresh-token, consent, break-glass, audit-query, or frontend work.
- [ ] Search audit/service/controller code for identifier values, names, raw headers, or payload logging.
- [ ] Commit plan artifact as `docs: add Slice 2.1 patient internal API plan`.
- [ ] Commit implementation as `feat: add audited patient internal API`.

## Self-Review Checklist

- Every patient endpoint authenticates, authorizes through `PolicyEvaluator`, and audits both allowed and denied outcomes.
- Cross-organization reads and searches fail closed with `404`/empty results, never leaking other tenants' data.
- Clinical writes are transactional with their audit rows.
- Audit rows carry patient/resource IDs but no demographic or identifier payloads.
- Role and scope rules match the V1 role model; admins do not get implicit chart access.
- FHIR remains a future boundary; nothing in this slice serializes FHIR resources.
