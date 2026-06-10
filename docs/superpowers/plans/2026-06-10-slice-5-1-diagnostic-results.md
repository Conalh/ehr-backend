# Slice 5.1 Diagnostic Results Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the Slice 5 exit criteria: attach a synthetic diagnostic result to a placed order — completing the order atomically — with linked result observations, and expose FHIR `DiagnosticReport` read/compartment-search.

**Architecture:** `diagnostic_reports` follows the compartment pattern with a required composite same-org FK to `orders`; `diagnostic_report_results` links reports to result `observations` through composite same-org FKs, with the service additionally verifying every linked observation belongs to the order's patient. `POST /api/v1/orders/{orderId}/results` (the design spec's API sketch) requires the order to be `active`, and in one transaction: creates the report + result links, transitions the order to `completed` (with its prior-state revision and `updated` provenance), records `created` provenance for the report, and audits the `CREATE`. Policy: `DIAGNOSTIC_REPORT` clinician-only read/write (`policy-spine-v11`). The provenance FHIR type map gains `ORDER`→`ServiceRequest` and `DIAGNOSTIC_REPORT`→`DiagnosticReport` so provenance targets render correctly.

**Standards Notes:** Status vocabulary is the FHIR R4 `DiagnosticReport.status` subset: `partial`, `final` (default), `amended`, `entered-in-error`. FHIR mapping renders `code` (LOINC), `subject`, optional `encounter`, `result` references to served `Observation` resources, `conclusion`, `issued`, and `meta`. `basedOn` is deferred until FHIR `ServiceRequest` is served (orders remain internal-first).

---

## File Structure

- Create `src/main/resources/db/migration/V13__diagnostic_report_foundation.sql`.
- Create `src/main/kotlin/dev/ehr/diagnostics/`: `DiagnosticReportIds.kt`, `DiagnosticReportEnums.kt`, `DiagnosticReportModels.kt`, `DiagnosticReportRepository.kt`, `DiagnosticReportService.kt`, `DiagnosticReportController.kt`.
- Modify `PolicyModels.kt`/`PolicyEvaluator.kt`: `DIAGNOSTIC_REPORT` rules, `policy-spine-v11` + literal updates.
- Modify `ProvenanceFhirMapper`: add ORDER/DIAGNOSTIC_REPORT type mappings.
- Create `src/main/kotlin/dev/ehr/fhir/DiagnosticReportFhirMapper.kt`, `DiagnosticReportFhirController.kt`.
- Create diagnostics schema/API and DiagnosticReport FHIR test suites.

## Acceptance Criteria

- Schema: `diagnostic_reports` (compartment shape, required same-org `order_id` FK, constrained status, required `code_concept_id`, nonblank nullable `conclusion_text`, `issued_at`, version/audit columns, `unique (organization_id, id)`, tenant-leading indexes) and `diagnostic_report_results` (composite same-org FKs to reports and observations, unique report+observation, ordinal-ordered).
- `POST /api/v1/orders/{orderId}/results` (body: `codeConceptId`, optional `conclusionText`/`encounterId`, `resultObservationIds` non-empty): `201`; order becomes `completed` with revision + `updated` provenance; report gets `created` provenance; `404` cross-tenant/missing order or encounter; `422` order not `active`; `400` unknown concepts, observations outside the order's patient, or empty results; everything atomic.
- `GET /api/v1/diagnostic-reports/{id}` (includes ordered result observation IDs) and `GET /api/v1/patients/{id}/diagnostic-reports` — standard `200`/`404`/`403` + audit parity (resource type `DIAGNOSTIC_REPORT`).
- FHIR: `GET /fhir/r4/DiagnosticReport/{id}` and `?patient=` — status/code/subject/encounter/result references/conclusion/issued/meta, `OperationOutcome` errors, audit parity via the service.
- Policy: clinician-only read/write with `user|system / DiagnosticReport|* . read|write` scopes; `policy-spine-v11`.
- All prior suites green apart from policy-version literals.

## Intentional Deferrals

- No report amendment endpoints (pattern exists; later), no `basedOn` until FHIR ServiceRequest, no media/presentedForm attachments, no partial-then-final workflows, no performer.

## Tasks

- [ ] Failing schema + API + FHIR tests covering the matrix above (incl. order auto-completion provenance chain and atomicity on failure).
- [ ] Implement V13, diagnostics package, policy bump + literals, provenance type-map additions, FHIR mapper/controller.
- [ ] Focused + full suites; commit plan as `docs: add Slice 5.1 diagnostic results plan`; implementation as `feat: add diagnostic results with FHIR DiagnosticReport boundary`.

## Self-Review Checklist

- Result attachment is atomic: report + links + order completion + provenance + audit, or nothing.
- Result observations are tenant- and patient-consistent with the order.
- The Slice 5 exit criteria are demonstrably met end to end.
