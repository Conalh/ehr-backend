# Slice 3.0 Encounter Domain Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the first clinical-event aggregate: tenant-scoped, patient-compartment-keyed encounters with a coded class, an explicit status lifecycle with validated transitions, Kotlin domain models, repository, fixtures, and isolation tests.

**Architecture:** Slice 3.0 introduces the patient-compartment pattern every later clinical table will follow: `organization_id` + `patient_id` on the row, with a composite foreign key to `patients (organization_id, id)` so a row can never reference another tenant's patient. Encounter class is a real terminology reference (`codeable_concepts`, v3-ActCode), not a display string. Status transitions are validated in the repository with optimistic concurrency (status+version guarded update); the schema enforces what it can (valid statuses, finished-requires-end, period ordering). `encounters (organization_id, id)` gets a unique key so future clinical tables (conditions, observations, notes) can composite-FK to encounters the same way. No HTTP endpoints yet — that is Slice 3.1.

**Standards Notes:** Status values are the FHIR R4 `Encounter.status` subset this product supports: `planned`, `in-progress`, `finished`, `cancelled`, `entered-in-error`. Class codes come from HL7 v3-ActCode (`AMB`, `IMP`, `EMER`, ...) via the existing canonical code system entry. Care-team/assignment-based compartment authorization is deliberately deferred to a later hardening slice (recorded decision); Slice 3 keeps org + role + scope policy.

**Tech Stack:** Kotlin, Spring Boot 3.5.14, Spring JDBC/JdbcTemplate, PostgreSQL 16, Flyway, Testcontainers, JUnit 5.

---

## File Structure

- Create `src/main/resources/db/migration/V5__encounter_foundation.sql`: encounters schema.
- Create `src/main/kotlin/dev/ehr/encounter/EncounterIds.kt`: `EncounterId`.
- Create `src/main/kotlin/dev/ehr/encounter/EncounterEnums.kt`: `EncounterStatus` with db values and allowed-transition rules.
- Create `src/main/kotlin/dev/ehr/encounter/EncounterModels.kt`: `Encounter`, `EncounterCreateCommand`, `EncounterTransitionCommand`.
- Create `src/main/kotlin/dev/ehr/encounter/EncounterRepository.kt`: tenant-scoped persistence with transition validation.
- Create `src/test/kotlin/dev/ehr/encounter/EncounterSchemaMigrationTest.kt`: schema and constraint coverage.
- Create `src/test/kotlin/dev/ehr/encounter/EncounterRepositoryIntegrationTest.kt`: repository behavior, lifecycle, and tenant isolation.
- Create `src/test/kotlin/dev/ehr/testsupport/EncounterTestFixtures.kt`: encounter-class concept + two-org encounter fixtures.

## Acceptance Criteria

- `encounters` table exists with:
  - `id uuid primary key`
  - `organization_id uuid not null references organizations(id)`
  - `patient_id uuid not null`
  - composite FK `(organization_id, patient_id)` referencing `patients (organization_id, id)`
  - `status text not null` constrained to the five supported values
  - `class_concept_id uuid not null references codeable_concepts(id)`
  - `period_start timestamptz not null`, `period_end timestamptz`
  - `version`, `created_at/by`, `updated_at/by` per the standard clinical row contract
  - `unique (organization_id, id)` for future same-org composite FKs
- Schema constraints enforce: valid status; `period_end >= period_start` when present; `finished` requires `period_end`; positive version; cross-organization patient references impossible.
- Tenant-leading indexes exist, including `(organization_id, patient_id, period_start desc)` for timeline reads.
- `EncounterStatus` encodes allowed transitions:
  - `PLANNED` → `IN_PROGRESS`, `CANCELLED`, `ENTERED_IN_ERROR`
  - `IN_PROGRESS` → `FINISHED`, `ENTERED_IN_ERROR`
  - `FINISHED` → `ENTERED_IN_ERROR`
  - `CANCELLED` → `ENTERED_IN_ERROR`
  - `ENTERED_IN_ERROR` → none (terminal)
- `EncounterRepository` supports:
  - `create(command)` — status restricted to `PLANNED` or `IN_PROGRESS`, insert bound to the patient row inside the same organization (cross-tenant create fails);
  - `findById(TenantScope, EncounterId)` — wrong tenant returns null;
  - `findByPatient(TenantScope, PatientId)` — newest-first timeline order, wrong tenant returns empty;
  - `transition(TenantScope, EncounterId, command)` — validates the transition, requires `periodEnd` when finishing, increments `version`, updates `updated_at`/`updated_by`, guarded by current status+version (stale transitions fail).
- Invalid transitions throw before touching the database.
- Encounter class references a real `CodeableConcept` built from the registered v3-ActCode canonical system.
- Existing identity/security/audit/terminology/patient/FHIR tests remain green.

## Intentional Deferrals

- No HTTP encounter endpoints (Slice 3.1).
- No `ENCOUNTER` policy resource type or audit wiring (Slice 3.1).
- No FHIR Encounter mapping or search (Slice 3.2).
- No other clinical tables (conditions, allergies, observations, notes).
- No encounter type/serviceType/participant/location/reason fields.
- No provenance service or revision history (Slice 4).
- No closed-encounter immutability rules beyond status transitions.
- No care-team or assignment-based compartment authorization (deferred hardening slice).
- No RLS policies.
- No frontend.

## Task 1: Write Failing Encounter Schema Tests

- [ ] Add migration test proving `encounters` exists with required columns and nullability.
- [ ] Add DB constraint tests for invalid status, inverted period, finished-without-end, non-positive version, and a patient reference from another organization.
- [ ] Add index assertions for tenant-leading indexes.
- [ ] Run targeted encounter tests and confirm failures are due to missing schema.

## Task 2: Write Failing Encounter Repository Tests

- [ ] Add create+read tests in the correct tenant, including class concept linkage.
- [ ] Add wrong-tenant read-null and wrong-tenant timeline-empty tests.
- [ ] Add cross-tenant create failure test (patient in another organization).
- [ ] Add timeline ordering test with multiple encounters.
- [ ] Add lifecycle tests: planned→in-progress→finished happy path; finishing requires period end; cancelled and entered-in-error paths; invalid transitions rejected; terminal entered-in-error; stale version/status transition fails.
- [ ] Add `EncounterTestFixtures` with an encounter-class concept helper and two-org fixtures.
- [ ] Run targeted tests and confirm failures are due to missing models/repository.

## Task 3: Implement Encounter Schema

- [ ] Add `V5__encounter_foundation.sql` with the table, constraints, composite FKs, unique key, and indexes above.

## Task 4: Implement Encounter Models And Repository

- [ ] Add `EncounterId`, `EncounterStatus` (with `canTransitionTo`), models, and commands.
- [ ] Add `EncounterRepository` with explicit SQL: same-org-bound insert (`insert ... select from patients`), tenant-scoped reads, and a status+version-guarded transition update.

## Task 5: Verify And Commit

- [ ] Run focused encounter tests.
- [ ] Run `.\gradlew.bat test --rerun-tasks`.
- [ ] Search for deferred endpoint, policy, FHIR, other-clinical-table, provenance, revision, RLS, care-team, or frontend work.
- [ ] Commit plan artifact as `docs: add Slice 3.0 encounter domain foundation plan`.
- [ ] Commit implementation as `feat: add encounter domain foundation`.

## Self-Review Checklist

- Encounters are tenant-scoped and patient-compartment-keyed; cross-tenant reads, timelines, and creates all fail closed.
- The composite-FK compartment pattern is reusable by every later clinical table.
- Lifecycle rules live in one place (`EncounterStatus`) and are enforced both in Kotlin and, where practical, in schema.
- Encounter class is coded terminology, not a display string.
- Optimistic guarding means concurrent transitions cannot silently clobber each other.
- No real PHI, no FHIR, no HTTP surface, no policy changes in this slice.
