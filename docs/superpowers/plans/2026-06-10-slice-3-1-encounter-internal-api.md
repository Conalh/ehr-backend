# Slice 3.1 Encounter Internal API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose the encounter lifecycle through policy-gated, tenant-scoped, fully audited internal endpoints: open an encounter for a patient, read it, list a patient's encounter timeline, and transition its status with optimistic concurrency.

**Architecture:** Slice 3.1 repeats the Slice 2.1 pattern over the encounter foundation. `PolicyEvaluator` gains an `ENCOUNTER` resource with the same role split as patients (clinician read/write, staff read). An `EncounterService` evaluates policy, records audit, and delegates to `EncounterRepository` under the caller's `TenantScope`; creates and transitions commit transactionally with their audit rows. Lifecycle failures map onto the design spec's error table: `409` for stale-version conflicts, `422` for clinical rule violations (invalid transition, finishing without an end), `404` for cross-tenant or missing rows, `403` + denial audit for policy denials. The audit operation vocabulary gains `UPDATE` (already allowed by the schema constraint).

**Standards Notes:** Encounters are scheduling-adjacent, so `STAFF` read access matches the V1 role model. Audit rows carry both the patient compartment key and the encounter resource ID, which FHIR `AuditEvent` mapping will need later. Compartment/care-team authorization remains deferred to the hardening slice (recorded decision).

**Tech Stack:** Kotlin, Spring Boot 3.5.14, Spring Security OAuth2 Resource Server, Spring MVC, Jakarta Validation, Spring JDBC, PostgreSQL 16, Testcontainers, MockMvc, JUnit 5.

---

## File Structure

- Create `src/main/kotlin/dev/ehr/encounter/EncounterController.kt`: endpoints and DTOs.
- Create `src/main/kotlin/dev/ehr/encounter/EncounterService.kt`: policy + audit + repository coordination.
- Modify `src/main/kotlin/dev/ehr/security/PolicyModels.kt`: add `ENCOUNTER` resource type.
- Modify `src/main/kotlin/dev/ehr/security/PolicyEvaluator.kt`: encounter read/write rules; bump `POLICY_VERSION` to `policy-spine-v3`.
- Modify `src/main/kotlin/dev/ehr/security/AuditModels.kt`: add `AuditOperation.UPDATE`.
- Create `src/test/kotlin/dev/ehr/encounter/EncounterApiIntegrationTest.kt`: endpoint, tenancy, lifecycle, and audit coverage.
- Modify `src/test/kotlin/dev/ehr/security/PolicyEvaluatorTest.kt`: encounter policy matrix + version literal.
- Modify `src/test/kotlin/dev/ehr/security/PolicyDecisionEndpointIntegrationTest.kt`: policy version literals only.

## Acceptance Criteria

- Endpoints (all under the authenticated `/api/v1` family):
  - `POST /api/v1/patients/{patientId}/encounters` — opens an encounter (`201`); body carries `classConceptId`, `periodStart`, optional initial `status` restricted to `PLANNED`/`IN_PROGRESS` (default `PLANNED`); `404` when the patient is missing or in another organization; `400` for an unknown class concept or invalid initial status.
  - `GET /api/v1/encounters/{encounterId}` — `200`, or `404` when missing/cross-tenant.
  - `GET /api/v1/patients/{patientId}/encounters` — newest-first timeline (`200`); `404` when the patient is not visible in the tenant.
  - `POST /api/v1/encounters/{encounterId}/status` — transition with `targetStatus`, optional `periodEnd`, optional `expectedVersion`; `200` on success, `409` on stale version, `422` on invalid transition or finishing without a period end, `404` when missing/cross-tenant.
- Policy rules: `ENCOUNTER` READ allowed for `CLINICIAN`/`STAFF`, WRITE for `CLINICIAN`, with `user|system / Encounter|* . read|write` scopes; `POLICY_VERSION` becomes `policy-spine-v3`.
- Audit rules (resource type `ENCOUNTER`, patient + encounter IDs populated):
  - create → `CREATE` + `SUCCESS`, transactional with the insert;
  - read → `READ` + `SUCCESS`; missing/cross-tenant read → `READ` + `FAILURE` with the requested ID and null patient;
  - timeline → `SEARCH` + `SUCCESS` with the patient ID;
  - transition → `UPDATE` + `SUCCESS`, transactional with the update; stale/invalid/not-found transitions → `UPDATE` + `FAILURE`;
  - policy denials → `AUTHORIZATION_DENIED` + `DENIED` and `403`;
  - unauthenticated → `401`, no audit.
- Audit rows and error bodies contain no clinical payloads or concept displays.
- Existing tests remain green apart from documented policy-version literal updates.

## Intentional Deferrals

- No FHIR Encounter mapping or search (Slice 3.2).
- No encounter update beyond status transitions; no participant/location/type/reason fields.
- No other clinical resources.
- No care-team/assignment compartment authorization (hardening slice).
- No provenance or revision history (Slice 4).
- No RLS, SMART, Keycloak, consent, break-glass, audit query API, or frontend.

## Task 1: Write Failing Policy Tests

- [ ] Extend `PolicyEvaluatorTest` with the encounter matrix: clinician read/write allowed, staff read allowed, staff write denied, admin read denied, missing scope denied, organization mismatch denied.
- [ ] Update policy version literals to `policy-spine-v3`.
- [ ] Run targeted tests and confirm failures are due to missing `ENCOUNTER` rules.

## Task 2: Write Failing Encounter API Tests

- [ ] Add `EncounterApiIntegrationTest` covering every acceptance bullet above, reusing the dev JWT, patient, and encounter fixtures.
- [ ] Run targeted tests and confirm failures are due to missing endpoints/service.

## Task 3: Implement Policy And Audit Extensions

- [ ] Add `PolicyResourceType.ENCOUNTER` and its rule rows; bump and document the policy version.
- [ ] Add `AuditOperation.UPDATE`.

## Task 4: Implement Encounter Service And Controller

- [ ] Add `EncounterService` mirroring `PatientService`: evaluate → audit denial or proceed under `TenantScope`; transactional create and transition; map repository outcomes onto `404`/`409`/`422`.
- [ ] Add `EncounterController` with validated DTOs (ISO-8601 instants, string IDs, enum names in, db values out).

## Task 5: Verify And Commit

- [ ] Run focused encounter, patient, and security tests.
- [ ] Run `.\gradlew.bat test --rerun-tasks`.
- [ ] Search for deferred FHIR, compartment, provenance, revision, RLS, SMART, or frontend work, and for payload leakage in audit/error paths.
- [ ] Commit plan artifact as `docs: add Slice 3.1 encounter internal API plan`.
- [ ] Commit implementation as `feat: add audited encounter internal API`.

## Self-Review Checklist

- Every encounter endpoint authenticates, authorizes, and audits both allowed and denied outcomes.
- Cross-tenant encounters and patients are unreachable; failures are indistinguishable from not-found.
- Creates and transitions are transactional with their audit rows.
- Lifecycle violations return `422`, stale versions `409`, exactly as the design spec's error table prescribes.
- Admins get no implicit encounter access; staff get read-only.
- FHIR remains untouched; the internal model stays the source of truth.
