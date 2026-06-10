# Slice 4.1 Version-Aware Updates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the first version-aware clinical update endpoints — condition updates (including resolve and entered-in-error), observation amendments, and note amendments — each capturing a prior-state revision and correction-vocabulary provenance, guarded by required optimistic concurrency.

**Architecture:** Updates follow the encounter-transition pattern generalized: the service loads the prior row inside the transaction, applies the change through a version-guarded repository update (`where version = expectedVersion`), snapshots the prior state into `resource_revisions`, and records provenance with the spine's correction vocabulary — `updated` for ordinary condition changes, `entered-in-error` when a condition's verification status is voided, `amended` for observation and note amendments (observation status is forced to `amended`). `expectedVersion` is **required** on update requests: clients must prove they saw the current version; mismatches return `409`. Audit records `UPDATE` success/failure as the encounter transition already does. Allergy and medication updates follow the same pattern in a later slice; no policy changes (existing WRITE rules govern updates).

**Standards Notes:** This closes the design spec's Slice 4 exit criterion: updating a clinical note or observation creates a new revision, and provenance identifies author, organization, timestamp, and target resource/version. FHIR `meta.versionId` continues to track row versions; amended observations surface as `status: amended` over FHIR with the new version.

**Tech Stack:** unchanged.

---

## File Structure

- Modify `ConditionRepository`/`ConditionService`/`ConditionController`: version-guarded `update`, `PATCH /api/v1/conditions/{id}`.
- Modify `ObservationRepository`/`ObservationService`/`ObservationController`: version-guarded `amend`, `POST /api/v1/observations/{id}/amend`.
- Modify `ClinicalNoteRepository`/`ClinicalNoteService`/`ClinicalNoteController`: version-guarded `amend`, `PATCH /api/v1/notes/{id}`.
- Create `src/test/kotlin/dev/ehr/provenance/VersionAwareUpdateApiIntegrationTest.kt`.

## Acceptance Criteria

- `PATCH /api/v1/conditions/{id}` with required `expectedVersion` and optional `clinicalStatus`/`verificationStatus`/`onsetDate`/`abatementDate`: `200` with incremented version; resolving sets `clinicalStatus=resolved`; voiding (`verificationStatus=ENTERED_IN_ERROR`) records `entered-in-error` provenance, other changes record `updated`; `409` stale version; `404` cross-tenant/missing; `400` inverted dates; `403` + audit for non-clinicians.
- `POST /api/v1/observations/{id}/amend` with required `expectedVersion` and exactly one new value: `200`, status becomes `amended`, version increments, `amended` provenance; value-shape errors `400`; `409`/`404`/`403` as above.
- `PATCH /api/v1/notes/{id}` with required `expectedVersion` and optional `title`/`contentText` (at least one, nonblank): `200`, version increments, `amended` provenance; `400` blank/no-op; `409`/`404`/`403` as above.
- Every successful update appends exactly one `resource_revisions` row snapshotting the *prior* state at the prior version, atomically with the update and its provenance and audit rows; failures leave nothing.
- Repeated updates produce a complete revision chain (v1, v2, ... snapshots) and provenance chain with correct `prior_resource_version` links.
- Audit: `UPDATE` + `SUCCESS` with patient/resource IDs on success; `UPDATE` + `FAILURE` on stale/missing; denials audited.
- Existing suites stay green; no policy version change.

## Intentional Deferrals

- No allergy/medication-statement updates (same pattern, later slice).
- No note addenda as linked documents, no observation correction-vs-amendment distinction, no revision restore or diff API.
- No FHIR write or FHIR `Provenance` (4.2).

## Tasks

- [ ] Failing integration tests covering the full matrix above, including the multi-update revision chain.
- [ ] Implement repository version-guarded updates (per-domain stale exceptions following the encounter pattern).
- [ ] Implement service update flows (prior-read + update + revision + provenance + audit in one transaction) and controller endpoints.
- [ ] Focused suites, full `--rerun-tasks` run, leak greps.
- [ ] Commit plan as `docs: add Slice 4.1 version-aware updates plan`; implementation as `feat: add version-aware clinical updates with revision capture`.

## Self-Review Checklist

- No update path exists without revision + provenance + audit in the same transaction.
- `expectedVersion` is mandatory — there is no last-writer-wins update.
- Correction vocabulary is applied semantically, not decoratively.
- Snapshots are prior state; the chain reconstructs history end to end.
