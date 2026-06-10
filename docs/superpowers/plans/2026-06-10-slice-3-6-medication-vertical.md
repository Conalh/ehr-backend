# Slice 3.6 Medication Vertical Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add medication statements as the fourth vertical clinical-resource slice — compartment-keyed schema with an RxNorm-coded medication, free-text dosage, audited internal API, and FHIR `MedicationStatement` read/compartment-search.

**Architecture:** `medication_statements` repeats the established compartment pattern (composite same-org FKs to `patients`/`encounters`, required `codeable_concepts` reference for the medication, FHIR-aligned status as constrained text, record-and-read only). Dosage is a free-text instruction column this slice — structured dosage (dose quantity, route, timing) is deferred until prescribing workflows exist (Slice 5 orders); free-text dosage is *instruction* text, not a coded clinical concept, so it does not violate the coded-terminology rule. `MedicationStatement` is chosen over `MedicationRequest` per the design spec's open decision #3: ordering semantics belong with Slice 5. Policy: clinician-only (`policy-spine-v7`).

**Standards Notes:** FHIR R4 `MedicationStatement.status` subset: `active`, `completed`, `stopped`, `on-hold`, `entered-in-error` (default `active`). Medication maps as `medicationCodeableConcept` (RxNorm via the canonical system). The encounter link maps to `MedicationStatement.context`. Effective period maps from day-precision date columns; `dateAsserted` from `recorded_at`.

**Tech Stack:** unchanged.

---

## File Structure

- Create `src/main/resources/db/migration/V9__medication_statement_foundation.sql`.
- Create `src/main/kotlin/dev/ehr/medication/`: `MedicationIds.kt`, `MedicationEnums.kt`, `MedicationModels.kt`, `MedicationStatementRepository.kt`, `MedicationStatementService.kt`, `MedicationStatementController.kt`.
- Modify `PolicyModels.kt`/`PolicyEvaluator.kt`: `MEDICATION` clinician-only rules, `policy-spine-v7`.
- Create `src/main/kotlin/dev/ehr/fhir/MedicationStatementFhirMapper.kt`, `MedicationStatementFhirController.kt`.
- Create medication schema/API/FHIR test suites mirroring prior verticals.
- Update policy-version literals in existing tests.

## Acceptance Criteria

- `medication_statements` table: compartment shape, `status` (default `active`, constrained), required `medication_concept_id`, nullable nonblank `dosage_text`, nullable `effective_start`/`effective_end` (`end >= start`), `recorded_at`, version/audit columns, `unique (organization_id, id)`, tenant-leading indexes including `(organization_id, patient_id, recorded_at desc)`.
- Repository create/findById/findByPatient newest-first, fail-closed tenancy.
- Internal API: `POST /api/v1/patients/{id}/medication-statements` (`201`/`404` cross-tenant patient or encounter/`400` unknown concept or inverted period), `GET /api/v1/medication-statements/{id}`, `GET /api/v1/patients/{id}/medication-statements` — audit parity (resource type `MEDICATION`).
- Policy: `MEDICATION` READ/WRITE clinician-only, `user|system / MedicationStatement|* . read|write` scopes, `policy-spine-v7`.
- FHIR: `GET /fhir/r4/MedicationStatement/{id}` and `?patient=` — status, `medicationCodeableConcept` (all codings + text), `subject`, `context`, `effectivePeriod`, `dosage[0].text`, `dateAsserted`, `meta`; `OperationOutcome` errors; audit parity via the service.
- All prior suites green apart from policy-version literals.

## Intentional Deferrals

- No `MedicationRequest`/ordering (Slice 5), no structured dosage (dose/route/timing/frequency), no medication reference resources, no reasonCode, no derivedFrom.
- No update/stop endpoints (Slice 4 revisions).
- No FHIR write, extra search params, paging; no other deferred-track items.

## Tasks

- [ ] Failing schema tests (status/dosage/period constraints, cross-org FKs) and API tests (full matrix incl. staff denied, all error paths) and FHIR tests (read shape, search both forms, errors).
- [ ] Implement V9, medication package, policy + version bump + literal updates, FHIR mapper/controller.
- [ ] Focused suites, full `--rerun-tasks` run, greps.
- [ ] Commit plan as `docs: add Slice 3.6 medication vertical plan`; implementation as `feat: add medication vertical with FHIR MedicationStatement boundary`.

## Self-Review Checklist

- Medication is real RxNorm-capable terminology; statuses leave as FHIR codes.
- Compartment isolation, audit parity, clinician-only policy, no FHIR bypass.
- Ordering and structured dosage deferred, not half-built.
