# Slice 3.3 Condition Vertical Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add conditions (problem list) as the first fully vertical clinical-resource slice: compartment-keyed schema with coded clinical concepts, repository, audited internal API, and FHIR `Condition` read/compartment-search — in one slice, since 3.0–3.2 established every pattern.

**Architecture:** `conditions` follows the 3.0 compartment pattern: composite FK to `patients (organization_id, id)` and an optional composite FK to `encounters (organization_id, id)` linking a problem to the encounter where it was recorded. The condition code is a required `codeable_concepts` reference (SNOMED CT/ICD-10-CM via the terminology foundation) — the first true clinical concept stored as coded terminology. Clinical status and verification status are fixed FHIR code-system values stored as constrained text (like encounter status) and emitted as proper codings at the FHIR boundary. Conditions are record-and-read this slice; versioned updates arrive with provenance/revisions in Slice 4. Policy: conditions are clinical-record data, so **CLINICIAN-only for read and write** — unlike scheduling-adjacent encounters, `STAFF` gets nothing, demonstrating per-resource policy granularity.

**Standards Notes:** FHIR R4 `Condition.clinicalStatus` subset: `active`, `inactive`, `remission`, `resolved` (system `condition-clinical`); `verificationStatus` subset: `provisional`, `confirmed`, `refuted`, `entered-in-error` (system `condition-ver-status`). `onset[x]`/`abatement[x]` map as day-precision dateTimes from internal date columns. `recordedDate` maps from `recorded_at`.

**Tech Stack:** Kotlin, Spring Boot 3.5.14, HAPI FHIR structures-r4 8.2.0, Spring JDBC, Flyway, PostgreSQL 16, Testcontainers, MockMvc, JUnit 5.

---

## File Structure

- Create `src/main/resources/db/migration/V6__condition_foundation.sql`.
- Create `src/main/kotlin/dev/ehr/condition/ConditionIds.kt`, `ConditionEnums.kt`, `ConditionModels.kt`, `ConditionRepository.kt`, `ConditionService.kt`, `ConditionController.kt`.
- Modify `src/main/kotlin/dev/ehr/security/PolicyModels.kt` / `PolicyEvaluator.kt`: `CONDITION` rules, version `policy-spine-v4`.
- Modify `src/main/kotlin/dev/ehr/terminology/CanonicalCodeSystems.kt`: condition-clinical and condition-ver-status URIs.
- Create `src/main/kotlin/dev/ehr/fhir/ConditionFhirMapper.kt`, `ConditionFhirController.kt`.
- Create `src/test/kotlin/dev/ehr/testsupport/TerminologyTestFixtures.kt`: generic find-or-create concept helper.
- Create condition schema/repository/API/FHIR-mapper/FHIR-API tests mirroring the encounter suites.
- Update policy-version literals in existing tests.

## Acceptance Criteria

- `conditions` table: `id`, `organization_id` (not null, FK), `patient_id` (not null, composite same-org FK), `encounter_id` (nullable, composite same-org FK to encounters), `clinical_status` (not null, constrained), `verification_status` (not null, default `confirmed`, constrained), `code_concept_id` (not null, FK to `codeable_concepts`), `onset_date`, `abatement_date` (check `abatement >= onset`), `recorded_at` (not null, default now), standard version/audit columns, `unique (organization_id, id)`, tenant-leading indexes including `(organization_id, patient_id, recorded_at desc)`.
- Repository: `create` (same-org-bound insert), `findById(TenantScope, ...)`, `findByPatient(TenantScope, ...)` newest-first by `recorded_at`; all wrong-tenant reads fail closed.
- Internal API:
  - `POST /api/v1/patients/{patientId}/conditions` → `201`; `404` cross-tenant patient or (when supplied) encounter not visible in tenant; `400` unknown code concept or invalid dates;
  - `GET /api/v1/conditions/{conditionId}` → `200`/`404`;
  - `GET /api/v1/patients/{patientId}/conditions` → newest-first problem list, `404` unknown patient.
- Policy: `CONDITION` READ and WRITE restricted to `CLINICIAN` with `user|system / Condition|* . read|write` scopes; staff and admins denied with `INSUFFICIENT_ROLE`; `POLICY_VERSION` = `policy-spine-v4` with documented literal updates.
- Audit: identical shape to encounters (`CREATE`/`READ`/`SEARCH`, patient + condition IDs, transactional create, denials audited, unauthenticated unaudited).
- FHIR:
  - `GET /fhir/r4/Condition/{id}` with `clinicalStatus`/`verificationStatus` as codings from their HL7 systems, `code` carrying all codings + text of the stored concept, `subject`, optional `encounter` reference, `onsetDateTime`/`abatementDateTime` (day precision), `recordedDate`, `meta`;
  - `GET /fhir/r4/Condition?patient=...` (both param forms) newest-first searchset Bundle;
  - `OperationOutcome` errors and audit parity via `ConditionService` (no FHIR bypass).
- All prior suites stay green apart from policy-version literals.

## Intentional Deferrals

- No condition update/resolve endpoints (versioned updates arrive with Slice 4 provenance/revisions).
- No severity, stage, body site, evidence, or category fields; no `category` search.
- No FHIR write; no extra search params; no paging.
- No batched concept resolution.
- No care-team compartment authorization, RLS, SMART, CapabilityStatement, US Core, frontend.

## Task 1: Failing Schema And Repository Tests

- [ ] Schema tests: columns/nullability, constraint failures (bad statuses, inverted dates, cross-org patient FK, cross-org encounter FK, non-positive version), tenant-leading indexes, composite unique key.
- [ ] Repository tests: create/read with code concept and optional encounter link, wrong-tenant null/empty, cross-tenant create failure, newest-first problem list.
- [ ] Add `TerminologyTestFixtures.findOrCreateConcept(system, code, display)`.

## Task 2: Failing API And FHIR Tests

- [ ] `ConditionApiIntegrationTest`: unauth 401 no audit; clinician create 201 + CREATE audit (with and without encounter link); staff read/create 403 INSUFFICIENT_ROLE audited; cross-tenant read/list/create 404; unknown concept 400; cross-tenant encounter link 404; list newest-first + SEARCH audit; scope-denied 403.
- [ ] `ConditionFhirMapperTest`: status/code/subject/encounter/dates/meta mapping, minimal condition without optionals.
- [ ] `ConditionFhirApiIntegrationTest`: read shape + audit, cross-tenant 404 OperationOutcome, non-UUID 404, forbidden 403, compartment search both forms + audit, cross-tenant patient search 404, missing param 400.

## Task 3: Implement

- [ ] `V6__condition_foundation.sql`.
- [ ] Condition IDs/enums/models/repository (mirroring encounter SQL patterns).
- [ ] Policy `CONDITION` rules + version bump + literal updates.
- [ ] `ConditionService`/`ConditionController` (mirroring encounter service/controller minus transitions).
- [ ] Canonical system constants, `ConditionFhirMapper`, `ConditionFhirController` on the shared FHIR support.

## Task 4: Verify And Commit

- [ ] Focused condition/encounter/patient/security/FHIR tests, then `.\gradlew.bat test --rerun-tasks`.
- [ ] Deferred-work and payload-leak greps over the condition and FHIR packages.
- [ ] Commit plan as `docs: add Slice 3.3 condition vertical plan`.
- [ ] Commit implementation as `feat: add condition vertical with FHIR Condition boundary`.

## Self-Review Checklist

- Conditions are compartment-keyed, tenant-isolated, and encounter-linkable only within the same organization.
- The condition code is real terminology; statuses leave the system as proper codings.
- CLINICIAN-only policy proves per-resource granularity; denials are audited.
- FHIR flows through the same service as the internal API.
- Updates are deferred, not half-built.
