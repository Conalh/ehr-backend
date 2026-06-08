# Slice 2.0 Patient Registry Domain Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the first patient registry domain foundation: tenant-scoped patient rows, patient identifiers, lifecycle/status rules, Kotlin domain models, repositories, fixtures, and cross-organization isolation tests.

**Architecture:** Slice 2.0 creates the internal patient source of truth before FHIR Patient APIs. It introduces only patient registry persistence and repository behavior. It does not expose internal HTTP patient endpoints, FHIR resources, FHIR search, patient-compartment authorization, provenance service, revision history, RLS, SMART/Keycloak, Synthea import, or frontend.

**Tech Stack:** Kotlin, Spring Boot 3.5.14, Spring JDBC/JdbcTemplate, PostgreSQL 16, Flyway, Testcontainers, JUnit 5.

---

## File Structure

- Create `src/main/resources/db/migration/V4__patient_registry_foundation.sql`: patients and patient identifiers schema.
- Create `src/main/kotlin/dev/ehr/patient/PatientIds.kt`: patient typed IDs.
- Create `src/main/kotlin/dev/ehr/patient/PatientEnums.kt`: patient status, administrative gender, and identifier use.
- Create `src/main/kotlin/dev/ehr/patient/PatientModels.kt`: patient, patient identifier, and create command types.
- Create `src/main/kotlin/dev/ehr/patient/PatientRepository.kt`: tenant-scoped patient persistence.
- Create `src/test/kotlin/dev/ehr/patient/PatientSchemaMigrationTest.kt`: schema and constraint coverage.
- Create `src/test/kotlin/dev/ehr/patient/PatientRepositoryIntegrationTest.kt`: repository behavior and tenant isolation.
- Create or extend `src/test/kotlin/dev/ehr/testsupport/PatientTestFixtures.kt`: two-org patient fixture helper.

## Acceptance Criteria

- `patients` table exists with:
  - `id uuid primary key`
  - `organization_id uuid not null references organizations(id)`
  - `status text not null`
  - `given_name text not null`
  - `family_name text not null`
  - `birth_date date`
  - `administrative_gender text`
  - `version integer not null default 1`
  - `created_at timestamptz not null default now()`
  - `updated_at timestamptz not null default now()`
  - `created_by uuid references users(id)`
  - `updated_by uuid references users(id)`
- `patient_identifiers` table exists with:
  - `id uuid primary key`
  - `organization_id uuid not null references organizations(id)`
  - `patient_id uuid not null`
  - `system text not null`
  - `value text not null`
  - `use text`
  - `type_concept_id uuid references codeable_concepts(id)`
  - `assigner_text text`
  - `period_start date`
  - `period_end date`
  - `created_at timestamptz not null default now()`
- Patient identifiers enforce same-organization ownership through a composite foreign key or equivalent guard.
- Schema constraints enforce:
  - patient and identifier `organization_id` are not null;
  - patient status is valid;
  - administrative gender is valid when present;
  - identifier use is valid when present;
  - patient names, identifier system, and identifier value are nonblank;
  - optional assigner text is nonblank when present;
  - `period_end >= period_start` when both are present;
  - identifier uniqueness per `organization_id`, `system`, and `value`;
  - tenant-scoped indexes begin with `organization_id`.
- Typed IDs exist:
  - `PatientId`
  - `PatientIdentifierId`
- Domain types exist:
  - `Patient`
  - `PatientIdentifier`
  - `PatientCreateCommand`
  - `PatientIdentifierCreateCommand`
- Enum/value types exist:
  - `PatientStatus`: `ACTIVE`, `INACTIVE`, `ENTERED_IN_ERROR`
  - `PatientAdministrativeGender`: `MALE`, `FEMALE`, `OTHER`, `UNKNOWN`
  - `IdentifierUse`: `USUAL`, `OFFICIAL`, `TEMP`, `SECONDARY`, `OLD`
- `PatientRepository` supports:
  - `create(command)`
  - `findById(TenantScope, PatientId)`
  - `findByIdentifier(TenantScope, system, value)`
  - `addIdentifier(TenantScope, PatientId, command)`
  - `findIdentifiers(TenantScope, PatientId)`
- Repository reads require `TenantScope` and fail closed across organizations.
- Identifier `typeConceptId` can reference an existing terminology `CodeableConcept`.
- Existing identity/security/audit/terminology tests remain green.

## Intentional Deferrals

- No internal HTTP patient endpoints.
- No FHIR Patient mapping.
- No FHIR search.
- No FHIR `OperationOutcome`.
- No patient compartment authorization beyond tenant-scoped repository reads.
- No provenance service.
- No revision history tables.
- No RLS policies.
- No SMART launch, Keycloak, or refresh tokens.
- No consent or break-glass.
- No clinical tables beyond `patients` and `patient_identifiers`.
- No frontend.
- No Synthea import or realistic patient dataset.

## Task 1: Write Failing Patient Schema Tests

- [ ] Add migration test proving `patients` and `patient_identifiers` exist.
- [ ] Add schema tests for required columns and tenant nullability.
- [ ] Add DB constraint tests for invalid patient status, invalid administrative gender, invalid identifier use, blank names, blank identifier system/value, blank assigner text, invalid identifier period, and cross-organization identifier ownership.
- [ ] Add DB uniqueness test for duplicate identifier within the same organization.
- [ ] Run targeted patient tests and confirm failures are due to missing patient schema.

## Task 2: Write Failing Patient Repository Tests

- [ ] Add repository test for creating and reading a patient in the correct tenant.
- [ ] Add repository test proving wrong-tenant patient read returns null.
- [ ] Add repository test for adding identifiers and finding a patient by identifier in the correct tenant.
- [ ] Add repository test proving wrong-tenant identifier lookup returns null.
- [ ] Add repository test proving duplicate identifier in same organization fails.
- [ ] Add repository test proving same identifier in different organizations is allowed.
- [ ] Add repository test proving identifier `typeConceptId` can reference a terminology `CodeableConcept`.
- [ ] Add two-org patient fixtures.
- [ ] Run targeted patient tests and confirm failures are due to missing patient models/repository.

## Task 3: Implement Patient Schema

- [ ] Add `V4__patient_registry_foundation.sql`.
- [ ] Create `patients` with explicit lifecycle/status/version fields.
- [ ] Create `patient_identifiers` with tenant, patient, system/value, optional coded type, assigner, and period fields.
- [ ] Add check constraints, composite same-org foreign key, uniqueness, and tenant-leading indexes.

## Task 4: Implement Patient Models And Repository

- [ ] Add typed IDs.
- [ ] Add enums with lower-case DB values and `fromDb` helpers.
- [ ] Add model and create-command data classes.
- [ ] Add `PatientRepository` using `JdbcTemplate`.
- [ ] Make every tenant-scoped read take `TenantScope`.
- [ ] Make `addIdentifier` verify the patient exists inside the tenant before insert by relying on the composite foreign key and/or explicit organization-bound insert.

## Task 5: Verify And Commit

- [ ] Run focused patient tests.
- [ ] Run existing identity/security/audit/terminology focused tests.
- [ ] Run `.\gradlew.bat test --rerun-tasks`.
- [ ] Search for deferred HTTP, FHIR, OperationOutcome, provenance service, revision, RLS, SMART, Keycloak, refresh-token, consent, break-glass, broader clinical-table, frontend, or Synthea work.
- [ ] Commit plan artifact as `docs: add Slice 2.0 patient registry plan`.
- [ ] Commit implementation as `feat: add patient registry foundation`.

## Self-Review Checklist

- Patient records are tenant-scoped and cannot be read through a wrong tenant.
- Patient identifiers are not tenancy keys; they are unique only within an organization.
- The schema can later support patient merge by linking through stable internal UUIDs.
- Identifier semantics use `system` and `value`, with optional coded type, not display strings only.
- No real PHI, realistic patient dataset, or clinical recommendation behavior is introduced.
- FHIR remains a future boundary over the internal model, not the persistence model itself.
