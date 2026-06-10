# Slice 3.4 Allergy Vertical Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add allergies/intolerances as the second vertical clinical-resource slice — compartment-keyed schema with a coded allergen, audited internal API, and FHIR `AllergyIntolerance` read/compartment-search — structurally mirroring the Slice 3.3 condition vertical.

**Architecture:** `allergies` repeats the condition pattern: composite same-org FKs to `patients` and (optionally) `encounters`, a required `codeable_concepts` reference for the allergen substance (SNOMED CT/RxNorm), FHIR-aligned status columns stored as constrained text, record-and-read only (versioned updates arrive with Slice 4 provenance/revisions). Single-valued `category` is a documented simplification of FHIR's repeating category. Policy: allergy lists are clinical-record data — **CLINICIAN-only read/write**, like conditions; policy version bumps to `policy-spine-v5`.

**Standards Notes:** FHIR R4 `AllergyIntolerance.clinicalStatus`: `active`, `inactive`, `resolved` (system `allergyintolerance-clinical`); `verificationStatus`: `unconfirmed`, `confirmed`, `refuted`, `entered-in-error` (system `allergyintolerance-verification`). `category`: `food`, `medication`, `environment`, `biologic`. `criticality`: `low`, `high`, `unable-to-assess`. The compartment reference is `AllergyIntolerance.patient` (not `subject`). Scope strings use the FHIR resource name: `user/AllergyIntolerance.read` etc.

**Tech Stack:** unchanged from 3.3.

---

## File Structure

- Create `src/main/resources/db/migration/V7__allergy_foundation.sql`.
- Create `src/main/kotlin/dev/ehr/allergy/`: `AllergyIds.kt`, `AllergyEnums.kt`, `AllergyModels.kt`, `AllergyRepository.kt`, `AllergyService.kt`, `AllergyController.kt`.
- Modify `PolicyModels.kt` / `PolicyEvaluator.kt`: `ALLERGY` rules, `policy-spine-v5`.
- Modify `CanonicalCodeSystems.kt`: allergy clinical/verification system URIs.
- Create `src/main/kotlin/dev/ehr/fhir/AllergyFhirMapper.kt`, `AllergyFhirController.kt`.
- Create allergy schema/repository/API/FHIR-mapper/FHIR-API test suites mirroring 3.3.
- Update policy-version literals in existing tests.

## Acceptance Criteria

- `allergies` table: standard compartment shape (`organization_id`, `patient_id` composite FK, nullable `encounter_id` composite FK), `clinical_status` (default `active`), `verification_status` (default `confirmed`), required `code_concept_id`, nullable `category` and `criticality` (constrained), nullable `onset_date`, `recorded_at`, version/audit columns, `unique (organization_id, id)`, tenant-leading indexes.
- Repository create/findById/findByPatient with fail-closed tenancy, newest-first list.
- Internal API: `POST /api/v1/patients/{id}/allergies` (`201`/`404`/`400`), `GET /api/v1/allergies/{id}` (`200`/`404`), `GET /api/v1/patients/{id}/allergies` (`200`/`404`) — audit parity with conditions (resource type `ALLERGY`).
- Policy: `ALLERGY` READ/WRITE clinician-only with `user|system / AllergyIntolerance|* . read|write` scopes; staff/admin denied; version `policy-spine-v5` with literal updates.
- FHIR: `GET /fhir/r4/AllergyIntolerance/{id}` and `?patient=` search — statuses as codings from their HL7 systems, `code` from the stored concept, `patient`/`encounter` references, `category`, `criticality`, `onsetDateTime`, `recordedDate`, `meta`; `OperationOutcome` errors; audit parity via `AllergyService`.
- All prior suites green apart from policy-version literals.

## Intentional Deferrals

- No update/resolve endpoints (Slice 4).
- No reaction/manifestation sub-structures, no `type` (allergy vs intolerance), no repeating categories, no `lastOccurrence`.
- No FHIR write, extra search params, paging, batched concept resolution.
- No care-team compartment authorization, RLS, SMART, CapabilityStatement, US Core, frontend.

## Tasks

- [ ] Failing schema/repository tests (constraints incl. invalid category/criticality, cross-org patient and encounter FKs, tenancy, ordering) + reuse `TerminologyTestFixtures`.
- [ ] Failing API tests (auth/audit/tenancy/validation matrix incl. staff denied) and FHIR tests (read shape, cross-tenant, non-UUID, forbidden, both search forms, missing param).
- [ ] Implement V7 migration, allergy domain package, policy rules + version bump + literal updates, canonical systems, FHIR mapper/controller on shared support.
- [ ] Focused suites, then `.\gradlew.bat test --rerun-tasks`; deferred-work and leak greps.
- [ ] Commit plan as `docs: add Slice 3.4 allergy vertical plan`; implementation as `feat: add allergy vertical with FHIR AllergyIntolerance boundary`.

## Self-Review Checklist

- Allergies are compartment-keyed, tenant-isolated, encounter-linkable same-org only.
- Allergen is real terminology; statuses/category/criticality leave as proper codes.
- Clinician-only policy enforced and audited; FHIR has no bypass.
- Updates deferred, not half-built.
