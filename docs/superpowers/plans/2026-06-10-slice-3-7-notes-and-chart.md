# Slice 3.7 Clinical Notes And Chart Timeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the Slice 3 exit criteria: encounter-attached clinical notes with a coded type, the internal patient chart endpoint aggregating the longitudinal record, and FHIR `DocumentReference` read/compartment-search.

**Architecture:** `clinical_notes` follows the compartment pattern with a required coded note type (LOINC document-type concepts) and plain-text content (binary attachments deferred). Notes are created through the encounter (`POST /api/v1/encounters/{id}/notes`, per the design spec's internal API) — the compartment patient is derived from the encounter, so a note can never disagree with its encounter's patient. The chart endpoint (`GET /api/v1/patients/{id}/chart`) is a tenant-scoped composite read over all six clinical repositories; it gets its own `CHART` policy resource (clinician-only, wildcard read scopes since no single-resource scope covers a whole chart) and one audited `READ` event with resource type `CHART` and the patient compartment ID. Existing per-resource response DTO mappers become public so the chart reuses them instead of duplicating mapping. Policy version bumps to `policy-spine-v8` (adds `NOTE` and `CHART`).

**Standards Notes:** FHIR R4 `DocumentReference.status`: `current`, `superseded`, `entered-in-error` (default `current`). Note type maps from the stored LOINC concept; `subject`, `context.encounter`, `date` (authored), `description` (title), and `content[0].attachment` carry `text/plain` base64 data. The exit criterion — a synthetic patient chart shows a longitudinal timeline — is met by the chart endpoint returning newest-first encounters, problems, allergies, medications, observations, and notes in one response.

**Tech Stack:** unchanged.

---

## File Structure

- Create `src/main/resources/db/migration/V10__clinical_note_foundation.sql`.
- Create `src/main/kotlin/dev/ehr/note/`: `NoteIds.kt`, `NoteEnums.kt`, `NoteModels.kt`, `ClinicalNoteRepository.kt`, `ClinicalNoteService.kt`, `ClinicalNoteController.kt`.
- Create `src/main/kotlin/dev/ehr/chart/ChartService.kt`, `ChartController.kt`.
- Modify per-resource controllers: make `toResponse` extensions public for chart reuse.
- Modify `PolicyModels.kt`/`PolicyEvaluator.kt`: `NOTE` (clinician-only, DocumentReference scopes) and `CHART` (clinician-only, wildcard read scopes), `policy-spine-v8`.
- Create `src/main/kotlin/dev/ehr/fhir/DocumentReferenceFhirMapper.kt`, `DocumentReferenceFhirController.kt`.
- Create note schema/API, chart API, and DocumentReference FHIR test suites.
- Update policy-version literals in existing tests.

## Acceptance Criteria

- `clinical_notes` table: compartment shape (patient + required encounter composite same-org FKs), `status` (default `current`, constrained), required `type_concept_id`, nonblank `title` and `content_text`, `authored_at` (default now), version/audit columns, `unique (organization_id, id)`, tenant-leading indexes including `(organization_id, patient_id, authored_at desc)`.
- Internal note API: `POST /api/v1/encounters/{encounterId}/notes` (`201`, patient derived from the encounter; `404` cross-tenant/missing encounter; `400` unknown type concept or blank title/content), `GET /api/v1/notes/{id}`, `GET /api/v1/patients/{id}/notes` — audit parity (resource type `NOTE`).
- Chart endpoint: `GET /api/v1/patients/{patientId}/chart` returns demographics + identifiers, encounters, conditions, allergies, medication statements, observations, and notes (each newest-first); `404` unknown/cross-tenant patient; `403` + denied audit for staff/admin or missing wildcard scope; success audits one `READ` with resource type `CHART` and the patient ID.
- Policy: `NOTE` READ/WRITE clinician-only (`user|system / DocumentReference|* . read|write`); `CHART` READ clinician-only (`user/*.read`, `system/*.read`); `policy-spine-v8`.
- FHIR: `GET /fhir/r4/DocumentReference/{id}` and `?patient=` — status, type (codings + text), `subject`, `context.encounter`, `date`, `description`, `content[0].attachment` (`text/plain`, base64 data); `OperationOutcome` errors; audit parity via the note service.
- All prior suites green apart from policy-version literals.

## Intentional Deferrals

- No binary/PDF attachments, no addenda/amendments (Slice 4 revisions), no note signing.
- No chart pagination, date-range filters, or section toggles; no FHIR `$everything`.
- No FHIR write, paging; no other deferred-track items.

## Tasks

- [ ] Failing schema tests (constraints, required encounter, cross-org FKs) and API tests (note matrix; chart composite incl. tenancy, audit, and role/scope denials) and FHIR DocumentReference tests.
- [ ] Implement V10, note package, chart service/controller (reusing public DTO mappers), policy + version bump + literal updates, FHIR mapper/controller.
- [ ] Focused suites, full `--rerun-tasks` run, greps.
- [ ] Commit plan as `docs: add Slice 3.7 notes and chart plan`; implementation as `feat: add clinical notes, chart timeline, and FHIR DocumentReference`.

## Self-Review Checklist

- Notes always belong to an encounter and its patient; cross-tenant paths fail closed.
- The chart is a single audited compartment read, clinician-only, with no per-section policy bypass.
- Note content never appears in audit rows, logs, or error diagnostics.
- Slice 3 exit criteria are met: longitudinal chart + coherent FHIR bundles for the compartment.
