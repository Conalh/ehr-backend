# Slice AS4 FHIR Bulk Export Protocol Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The final authorization-server slice: the FHIR Bulk Data
kickoff/status protocol over the export engine that has existed since
Slice 8, authorized like every other export surface (clinicians and AS1
backend-services tokens). After this, the AS arc is complete and the
Inferno gap report gets rewritten.

**Decided-and-recorded:**

1. **System-level `$export` only.** `GET /fhir/r4/$export` maps onto the
   existing whole-organization export. `Group/[id]/$export` and
   `Patient/$export` need resources/semantics this model does not have —
   recorded gaps, not stubs.
2. **`_type` is refused, not ignored.** The engine exports all nine types;
   silently ignoring a filter the client asked for would misrepresent the
   output. Per the Bulk Data spec's let-out, an unsupported `_type` returns
   400 with an explanatory OperationOutcome.
3. **Protocol mapping:** kickoff requires `Prefer: respond-async` (400
   otherwise) → 202 with `Content-Location: {base}/fhir/r4/$export-status/{jobId}`.
   Status: 202 + `X-Progress` while pending/in-progress; 200 + the Bulk Data
   manifest when complete (`transactionTime` = requestedAt, `request`,
   `requiresAccessToken: true`, `output[{type,url,count}]` pointing at the
   existing authenticated NDJSON download endpoints, `error: []`); 500 +
   OperationOutcome when the job failed. DELETE (cancel) is unsupported —
   the unmapped method 405s naturally; recorded.
4. **No policy change.** Kickoff/status route through the audited
   `ExportService` (EXPORT rules, wildcard scopes, SYSTEM_APP from AS1).

---

## File Structure

- Create `src/main/kotlin/dev/ehr/fhir/BulkExportFhirController.kt`.
- Create `src/test/kotlin/dev/ehr/fhir/BulkExportFhirIntegrationTest.kt`.
- Update `docs/conformance/inferno-g10.md` after the slice (separate commit): the gap matrix now reflects AS1–AS4.

## Acceptance Criteria

- Kickoff with a backend-services token + `Prefer: respond-async` → 202 + Content-Location; without the header → 400 OperationOutcome; with `_type` → 400 OperationOutcome (not-supported); staff → 403; unauthenticated → 401.
- Polling the status URL: 202 + `X-Progress` while running; 200 manifest on completion with `requiresAccessToken: true` and a Patient entry whose `count` matches and whose `url` serves NDJSON to the same token.
- Clinician tokens work identically; audit rows unchanged from the existing export surface (EXPORT resource type).
- Full suite green; no policy version bump.

## Intentional Deferrals

- `Group/[id]/$export`, `Patient/$export`, `_type`/`_since` filters, DELETE-cancel, `output-format` negotiation — all recorded in the refreshed gap report.

## Tasks

- [ ] Failing tests: kickoff/status/manifest/download matrix.
- [ ] Implement the controller; focused + full suites.
- [ ] Commit plan as `docs: add Slice AS4 bulk export protocol plan`; implementation as `feat: serve the FHIR bulk data export protocol`; then rewrite `docs/conformance/inferno-g10.md` as `docs: refresh the Inferno g10 gap report after the AS arc`.

## Self-Review Checklist

- The FHIR surface adds no authorization path of its own — every request routes through the audited service.
- The manifest never lies: `requiresAccessToken` is true and every URL it emits actually serves the file to an authorized caller.
- Unsupported protocol features are refused loudly, never silently absorbed.
