# Slice 4.0 Provenance And Revision Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the provenance spine and revision history foundation: append-only `provenance_events` and `resource_revisions` tables exactly as the architecture spine prescribes, automatic provenance for every clinical create, and revision + provenance capture on the one existing mutation path (encounter status transitions).

**Architecture:** `provenance_events` carries the spine's minimal field set (target resource/version, activity, agent user/client, source type, prior version, synthetic-run id) and is append-only like `audit_events`. `resource_revisions` stores the prior row state as a bounded JSONB snapshot keyed by `(organization_id, resource_type, resource_id, version)` — current-state tables remain the source of truth; snapshots are history, which is the architecture's sanctioned JSONB use. A `ProvenanceRecorder` service derives `source_type` from the caller's role (clinician-authored / staff-recorded / system-imported) and appends provenance inside the same transaction as the clinical write — creates record `created`@v1; the encounter transition snapshots the prior row, then records `updated` with `prior_resource_version`. Update/amendment workflows for the other resources are Slice 4.1; FHIR `Provenance` is 4.2.

**Standards Notes:** Activity vocabulary: `created`, `updated` (plus the correction vocabulary `corrected`/`amended`/`addended`/`entered-in-error` reserved for 4.1). Source types per the spine: `clinician-authored`, `staff-recorded`, `system-imported`, `transformed`, `synthetic-generated`, `corrected`, `amended`, `addended`. `target_version` aligns with FHIR `meta.versionId` so FHIR `Provenance` can be rendered later without remodeling.

**Tech Stack:** unchanged; Jackson `ObjectMapper` for revision snapshots.

---

## File Structure

- Create `src/main/resources/db/migration/V11__provenance_and_revisions.sql`.
- Create `src/main/kotlin/dev/ehr/provenance/`: `ProvenanceModels.kt` (activity/source enums, event + revision records and commands), `ProvenanceRepository.kt`, `ResourceRevisionRepository.kt`, `ProvenanceRecorder.kt`.
- Modify the six clinical services (`PatientService`, `EncounterService`, `ConditionService`, `AllergyService`, `ObservationService`, `MedicationStatementService`, `ClinicalNoteService`) to record `created` provenance inside their create transactions.
- Modify `EncounterService.transition` to snapshot the prior encounter row and record `updated` provenance inside the transition transaction.
- Create `src/test/kotlin/dev/ehr/provenance/ProvenanceSchemaMigrationTest.kt` and `ProvenanceIntegrationTest.kt`.

## Acceptance Criteria

- `provenance_events`: spine field set with not-null `organization_id`, `patient_id`, `target_resource_type/_id/_version`, `activity`, `source_type`, `recorded_at`; constrained activity and source vocabularies; FKs for org/agent user/agent client; tenant-leading indexes on `(organization_id, target_resource_type, target_resource_id, target_version)` and `(organization_id, patient_id, recorded_at desc)`; append-only triggers (update/delete raise).
- `resource_revisions`: org/patient/resource keys, `version`, `snapshot jsonb` (object-typed check), `recorded_at`, `recorded_by`; `unique (organization_id, resource_type, resource_id, version)`; append-only triggers; tenant-leading indexes.
- Every clinical create (patient, encounter, condition, allergy, observation, medication statement, note) appends one `created` provenance event at version 1, with the agent user, role-derived source type, and the patient compartment ID, committed atomically with the insert (a failed create leaves no provenance).
- Encounter transitions append a `resource_revisions` snapshot of the *prior* row state at the prior version plus an `updated` provenance event carrying `prior_resource_version`, atomically with the update; failed/stale transitions leave neither.
- Repositories expose tenant-scoped reads (`findByTarget`, `findByPatient`, `findRevisions`) that fail closed across organizations.
- Provenance and revision rows contain no display strings of clinical concepts beyond the snapshot itself; snapshots never appear in logs or error bodies.
- Existing suites stay green (no policy change this slice — provenance is recorded with whatever decision authorized the write).

## Intentional Deferrals

- No update/amend/correct endpoints for non-encounter resources (4.1).
- No FHIR `Provenance` resource (4.2).
- No provenance query API beyond repository reads.
- No import/transform/synthetic-generation writers (source types reserved).
- No revision restore.

## Tasks

- [ ] Failing schema tests (columns, vocabularies, append-only triggers, unique key, indexes).
- [ ] Failing integration tests: provenance-on-create for each clinical resource; transition revision + provenance with prior version; rollback leaves nothing on failed create; cross-tenant reads fail closed.
- [ ] Implement V11, provenance package, recorder, and service wiring.
- [ ] Focused suites, full `--rerun-tasks` run, leak greps.
- [ ] Commit plan as `docs: add Slice 4.0 provenance and revision foundation plan`; implementation as `feat: add provenance spine and revision foundation`.

## Self-Review Checklist

- Provenance is transactional with clinical writes — no code path writes clinical data without it.
- Both history tables are append-only at the database layer.
- Snapshots capture prior state, not new state, and only on mutation.
- `target_version` lines up with row `version` for future FHIR `meta.versionId` rendering.
- Source-type vocabulary matches the spine exactly; unused members are reserved, not invented.
