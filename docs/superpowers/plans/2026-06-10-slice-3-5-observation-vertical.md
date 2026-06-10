# Slice 3.5 Observation Vertical Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add observations (vitals and labs) as the third vertical clinical-resource slice — the first with a polymorphic value model (quantity with UCUM unit, coded, or text; exactly one) and category-filtered compartment search, through schema, repository, audited internal API, and FHIR `Observation`.

**Architecture:** `observations` follows the compartment pattern (composite same-org FKs to `patients`/`encounters`, required LOINC `code_concept_id`). The value is modeled as a Kotlin sealed interface (`Quantity`/`Coded`/`Text`) so invalid states are unrepresentable, flattened to three nullable column groups with a schema check that exactly one is present and that quantities carry a UCUM unit. `category` is a required constrained text column (`vital-signs`, `laboratory`) because US Core requires it and the chart timeline filters on it; both internal list and FHIR search accept an optional category filter. Observations are record-and-read (amend/correct arrive with Slice 4 revisions). Policy: clinician-only read/write (`policy-spine-v6`); category-aware staff vitals access is a recorded future enhancement, not buildable until policy inputs carry resource attributes.

**Standards Notes:** FHIR R4 `Observation.status` subset: `preliminary`, `final`, `amended`, `cancelled`, `entered-in-error` (default `final`). Category codings use `observation-category`. `effectiveDateTime` maps from a required `effective_at` timestamptz. `valueQuantity` emits UCUM (`system` = `http://unitsofmeasure.org`, `code` = stored unit); `valueCodeableConcept` resolves a second terminology concept; `valueString` passes through. Search: `?patient=...&category=vital-signs`.

**Tech Stack:** unchanged.

---

## File Structure

- Create `src/main/resources/db/migration/V8__observation_foundation.sql`.
- Create `src/main/kotlin/dev/ehr/observation/`: `ObservationIds.kt`, `ObservationEnums.kt`, `ObservationModels.kt` (incl. sealed `ObservationValue`), `ObservationRepository.kt`, `ObservationService.kt`, `ObservationController.kt`.
- Modify `PolicyModels.kt`/`PolicyEvaluator.kt`: `OBSERVATION` clinician-only rules, `policy-spine-v6`.
- Modify `CanonicalCodeSystems.kt`: observation-category URI.
- Create `src/main/kotlin/dev/ehr/fhir/ObservationFhirMapper.kt`, `ObservationFhirController.kt`.
- Create observation schema/repository/API/FHIR-mapper/FHIR-API test suites.
- Update policy-version literals in existing tests.

## Acceptance Criteria

- `observations` table: compartment shape + `status` (default `final`, constrained), `category` (not null, `vital-signs`|`laboratory`), required `code_concept_id`, value columns (`value_quantity numeric` + `value_quantity_unit text`, `value_concept_id` FK, `value_text`) with checks: exactly one value present, quantity and unit always paired, text/unit nonblank; required `effective_at`; version/audit columns; `unique (organization_id, id)`; tenant-leading indexes including `(organization_id, patient_id, category, effective_at desc)`.
- Sealed `ObservationValue` (`Quantity(value, unit)`, `Coded(conceptId)`, `Text(value)`) round-trips through the repository.
- Repository: create / findById / findByPatient with optional category filter, newest-first by `effective_at`, fail-closed tenancy.
- Internal API: `POST /api/v1/patients/{id}/observations` (`201`; `404` cross-tenant patient/encounter; `400` unknown concepts, missing/multiple values, blank unit/text); `GET /api/v1/observations/{id}`; `GET /api/v1/patients/{id}/observations?category=` — audit parity (resource type `OBSERVATION`).
- Policy: `OBSERVATION` READ/WRITE clinician-only, `user|system / Observation|* . read|write` scopes, `policy-spine-v6`.
- FHIR: `GET /fhir/r4/Observation/{id}` and `?patient=...[&category=...]` — status/category/code/subject/encounter/effectiveDateTime/value[x] mapped, `OperationOutcome` errors, audit parity via `ObservationService`; invalid category param returns `400`.
- All prior suites green apart from policy-version literals.

## Intentional Deferrals

- No amend/correct/cancel endpoints (Slice 4 revisions).
- No components (BP systolic/diastolic as components), reference ranges, interpretations, dataAbsentReason, specimen, performer.
- No category-aware staff policy (needs attribute-bearing policy inputs; future hardening).
- No additional value types (boolean, integer, ratio, sampled data).
- No FHIR write, paging, batched concept resolution; no other deferred-track items.

## Tasks

- [ ] Failing schema/repository tests (value-exclusivity and unit-pairing constraints, category constraint, tenancy, category filter, ordering).
- [ ] Failing API + FHIR tests (full matrix: auth/audit/tenancy/validation incl. multiple-value 400, all three value types round-tripping, category filter on both APIs).
- [ ] Implement V8, observation package, policy + version bump + literal updates, canonical category system, FHIR mapper/controller.
- [ ] Focused suites, full `--rerun-tasks` run, deferred-work/leak greps.
- [ ] Commit plan as `docs: add Slice 3.5 observation vertical plan`; implementation as `feat: add observation vertical with FHIR Observation boundary`.

## Self-Review Checklist

- Exactly-one-value is enforced in both Kotlin (sealed type) and schema (check constraint).
- Quantities always carry UCUM units end to end.
- Category is required, constrained, and filterable on both API families.
- Compartment isolation, audit parity, clinician-only policy, no FHIR bypass.
- Amendments deferred, not half-built.
