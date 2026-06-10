# Slice 4.2 FHIR Provenance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose the provenance spine over FHIR: `GET /fhir/r4/Provenance/{id}`, `?target={Type}/{id}`, and `?patient={id}`, completing the design spec's Slice 4 deliverables.

**Architecture:** A `ProvenanceQueryService` adds the policy/audit gate (new `PROVENANCE` policy resource, clinician-only read — provenance reveals clinical-record metadata; `policy-spine-v9`) over the existing repositories; the FHIR controller maps the `target` parameter's FHIR resource type names onto internal target types (Patient→PATIENT, Encounter→ENCOUNTER, Condition→CONDITION, AllergyIntolerance→ALLERGY, Observation→OBSERVATION, MedicationStatement→MEDICATION, DocumentReference→NOTE). The mapper renders `target` (versioned reference), `recorded`, `agent.who` (user UUID as an identifier reference), and `activity` as a v3-DataOperation coding (`CREATE`/`UPDATE`) with the internal activity preserved in `activity.text`.

**Standards Notes:** FHIR R4 `Provenance.target` is a versioned reference (`{Type}/{id}/_history/{version}` rendered as reference string `{Type}/{id}` with extension-free `targetVersion` omitted; we render `Reference("{Type}/{id}")` and expose the version through `Provenance.target` display-free reference plus `recorded`). Activity codings use `http://terminology.hl7.org/CodeSystem/v3-DataOperation`.

---

## File Structure

- Add `ProvenanceRepository.findById(TenantScope, UUID)`.
- Create `src/main/kotlin/dev/ehr/provenance/ProvenanceQueryService.kt`.
- Modify `PolicyModels.kt`/`PolicyEvaluator.kt`: `PROVENANCE` read rule (clinician-only, `user|system / Provenance|* .read`), `policy-spine-v9` + test literal updates.
- Create `src/main/kotlin/dev/ehr/fhir/ProvenanceFhirMapper.kt`, `ProvenanceFhirController.kt`.
- Create `src/test/kotlin/dev/ehr/fhir/ProvenanceFhirApiIntegrationTest.kt`.

## Acceptance Criteria

- `GET /fhir/r4/Provenance/{id}`: `200` with target/agent/recorded/activity; `404` `OperationOutcome` cross-tenant/missing/non-UUID; `403` for staff/admin with denied audit.
- `GET /fhir/r4/Provenance?target=Condition/{id}` (all seven supported type names): chronological searchset Bundle; unknown type name or malformed target → `400`.
- `GET /fhir/r4/Provenance?patient={id}`: compartment bundle, `404` unknown/cross-tenant patient; missing both params → `400`.
- Reads/searches audited as `READ`/`SEARCH` on resource type `PROVENANCE`.
- All prior suites green apart from policy-version literals.

## Intentional Deferrals

- No FHIR Provenance write; no `entity` (source/import lineage rendering) until importers exist; no signature; no paging.

## Tasks

- [ ] Failing FHIR Provenance tests (read shape incl. activity codings, target search per type, patient search, errors, audit, tenancy).
- [ ] Implement repository read, query service, policy bump + literals, mapper, controller.
- [ ] Focused + full suites; commit plan as `docs: add Slice 4.2 FHIR Provenance plan`; implementation as `feat: add FHIR Provenance read and search boundary`.

## Self-Review Checklist

- Provenance is clinician-only and audited; no FHIR bypass.
- Target type mapping is the single source of FHIR↔internal name truth in the controller.
- Internal activities render faithfully (vocabulary in `activity.text`, operation coding standardized).
