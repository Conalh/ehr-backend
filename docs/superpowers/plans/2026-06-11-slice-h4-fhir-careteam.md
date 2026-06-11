# Slice H4 FHIR CareTeam Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Serve care-team relationships on the FHIR boundary: read-only `CareTeam` read + patient search, mirroring the established FHIR controller/mapper pattern. The design's H4 also names Postgres RLS; that is split into its own slice (H5) — RLS needs per-connection organization context (a GUC set inside every transaction), which is a different risk surface than a FHIR mapper and deserves its own plan.

**Architecture:** One `CareTeam` per patient compartment, not per membership row — FHIR's `CareTeam` is "the planned participants in a patient's care," which is exactly `findActiveByPatient`. The resource's logical id is the patient id (stable, distinct resource namespace); `status = active`, `subject = Patient/{id}`, one `participant` per active membership with a coded role (`urn:ehr:care-team-role` — `attending | covering | care-team`), the membership period, and `member` referenced by identifier (`urn:ehr:user-id`, display = user display name) — the same convention Provenance agents already use, since Practitioner is not served. A patient with no active memberships is still a valid (participant-less) team. Authorization, auditing, and tenancy come for free by routing through `CareTeamService.listForPatient` (CARE_TEAM READ: clinician + org-admin, `CareTeam` scopes, unknown patient → 404, SEARCH audit). No policy rule changes, so no `POLICY_VERSION` bump.

---

## File Structure

- Create `src/main/kotlin/dev/ehr/fhir/CareTeamFhirMapper.kt`, `CareTeamFhirController.kt`.
- Modify `FhirCapabilityRegistry.kt` (add `CareTeam` with the `patient` search param).
- Create `src/test/kotlin/dev/ehr/fhir/CareTeamFhirApiIntegrationTest.kt`; extend the conformance smoke test with a validated CareTeam example.

## Acceptance Criteria

- `GET /fhir/r4/CareTeam/{patientId}`: 200 with subject, coded participant roles, periods, and member identifiers + display names; 404 unknown/cross-tenant patient; 401 unauthenticated; 403 + denial audit for staff.
- `GET /fhir/r4/CareTeam?patient={id}` (logical id or `Patient/{id}`): searchset bundle, total 1, self link, fullUrl; 400 missing param.
- Ended memberships do not appear; encounter-derived and explicit members both do.
- CapabilityStatement lists `CareTeam` with the patient param (registry-driven, automatic).
- CareTeam output validates against the R4 core spec in the conformance test.
- Full suite green; no policy version change.

## Intentional Deferrals

- RLS → Slice H5 (per-connection org context + policies on clinical tables). No CareTeam write/history on the FHIR boundary (management stays on the REST API). No Practitioner resource.

## Tasks

- [ ] Failing tests: FHIR read/search matrix + conformance validation.
- [ ] Implement mapper, controller, registry entry.
- [ ] Focused + full suites; commit plan as `docs: add Slice H4 FHIR CareTeam plan`; implementation as `feat: serve FHIR R4 CareTeam read and patient search`.

## Self-Review Checklist

- The FHIR surface adds no authorization path of its own — every request routes through the audited service method.
- Participant member references use the established identifier convention, never a dangling `Practitioner/{id}` reference.
- Registry and CapabilityStatement stay in lockstep (review failure otherwise).
