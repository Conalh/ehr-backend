# Slice 8.0 Bulk Export Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the bulk export foundation: an `export_jobs` async state machine, an NDJSON writer that renders every served FHIR resource type for the requesting organization's patient population, tenant-scoped authenticated download URLs, and audit events for request, file creation, and download.

**Architecture:** `export_jobs` (pending → in-progress → completed | failed) and `export_job_files` (one row per resource type with count and storage path, composite same-org FK) follow the established tenancy patterns. Requesting an export inserts a `pending` job, audits the request, and fires a Spring `@Async` processor (separate bean, `@EnableAsync`) that streams each resource type through the existing FHIR mappers into `{storage-dir}/{jobId}/{Type}.ndjson`, records one file-creation audit row per file, and completes or fails the job (error messages never contain clinical payloads). Status (`GET /api/v1/export-jobs/{id}`) returns the state plus per-type file descriptors with download URLs; downloads stream NDJSON, tenant-scoped and audited. The audit vocabulary gains `EXPORT` and `SYSTEM` operations (both already allowed by the schema constraint). Policy: new `EXPORT` resource — clinician-only with wildcard scopes (population data, like the chart); `policy-spine-v14`. The design spec's "system app requests the export" waits on system-app principals (6.1 deferral); signed/expiring URLs wait on real file infrastructure — local development uses authenticated tenant-scoped routes, as the spec's "secure download URL pattern for local development".

**Standards Notes:** Output is FHIR Bulk Data-shaped (one resource per line, one file per type, `application/fhir+ndjson`), but the kickoff/status endpoints are internal API — the FHIR `$export` operation and `Accept: application/fhir+ndjson` plumbing arrive when system-app auth exists. Exported types are exactly the nine the CapabilityStatement declares.

---

## File Structure

- Create `src/main/resources/db/migration/V14__export_jobs.sql`.
- Create `src/main/kotlin/dev/ehr/export/`: `ExportModels.kt`, `ExportJobRepository.kt`, `ExportJobProcessor.kt`, `ExportService.kt`, `ExportController.kt`, `AsyncConfiguration.kt`.
- Modify `AuditModels.kt` (`EXPORT`, `SYSTEM` operations), `AuditEventService.kt` (principal-less export event recording for the processor), `PolicyModels.kt`/`PolicyEvaluator.kt` (`EXPORT` rules, `policy-spine-v14` + literal updates).
- Create `src/test/kotlin/dev/ehr/export/ExportApiIntegrationTest.kt`.

## Acceptance Criteria

- Schema: `export_jobs` (org FK, constrained status defaulting `pending`, requester, requested/started/completed timestamps, nonblank nullable `error_message`, `unique (organization_id, id)`) and `export_job_files` (composite same-org FK to jobs, FHIR `resource_type`, non-negative `resource_count`, `storage_path`, unique per job+type).
- `POST /api/v1/export-jobs` → `202` with the pending job; the async processor completes it, producing one NDJSON file per served resource type containing exactly the organization's rows rendered by the production FHIR mappers.
- `GET /api/v1/export-jobs/{id}` → status, timestamps, and file descriptors (type, count, download URL); `404` cross-tenant.
- `GET /api/v1/export-jobs/{id}/files/{type}` → `application/fhir+ndjson` stream; every line parses as the declared FHIR type; `404` cross-tenant/unknown type.
- Audit: request → `EXPORT`/`SUCCESS` on `EXPORT_JOB`; each file creation → `SYSTEM`/`SUCCESS` on `EXPORT_FILE` (recorded by the processor with the job's organization and requester); each download → `EXPORT`/`SUCCESS` on `EXPORT_FILE`; denials audited; unauthenticated `401` unaudited.
- Policy: `EXPORT` READ/WRITE clinician-only with wildcard scopes; staff/admin denied; `policy-spine-v14`.
- Processor failure marks the job `failed` with a payload-free message and audits `FAILURE`.
- Full suite green apart from policy-version literals.

## Intentional Deferrals

- No FHIR `$export` kickoff/status protocol, no `_type`/`_since` filters, no group/patient-level subsetting (organization-wide only), no signed or expiring URLs, no file retention/cleanup policy, no system-app requester (needs 6.x auth work), no parallel/chunked file writing.

## Tasks

- [ ] Failing API test: request → poll status to completion → verify per-type files, NDJSON parses as the right types and contains only the requesting org's resources → download audit; plus auth/tenancy matrix.
- [ ] Implement V14, export package (repository, async processor over the nine mappers, service, controller), audit additions, policy bump + literals.
- [ ] Focused + full suites; commit plan as `docs: add Slice 8.0 bulk export foundation plan`; implementation as `feat: add bulk export foundation with NDJSON output`.

## Self-Review Checklist

- Export jobs and files are tenant-isolated end to end; another org's job, status, and files are invisible.
- Every export step is audited with the requester attached, including the async ones.
- NDJSON output reuses the production mappers — no second serialization path to drift.
- Failure paths never leak clinical data into error messages or logs.
