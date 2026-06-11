# Slice H1 Care Team Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** First compartment-authorization slice per the accepted design (`docs/architecture/compartment-authorization.md`): the `care_team_memberships` table, repository, audited explicit-membership API, and automatic encounter-derived membership when a clinician opens an encounter. **No enforcement** — that is H2 (shadow) and H3 (enforced).

**Architecture:** Memberships follow the compartment pattern (composite same-org FK to `patients`). A relationship is an open-period row for (org, user, patient); ending sets `period_end` (no deletes — history is the audit story). One refinement over the design sketch: the active-uniqueness constraint becomes a **partial unique index** (`where period_end is null`) so an ended membership can be re-established. `EncounterService.open` ensures an `encounter-derived` membership for the acting clinician inside the encounter transaction (idempotent — no duplicate active rows). Policy: new `CARE_TEAM` resource, READ/WRITE for `CLINICIAN` and `ORG_ADMIN` with FHIR `CareTeam` scope names (`policy-spine-v15`). The 30-day auto-expiry of encounter-derived memberships ships with H3, when periods start mattering.

---

## File Structure

- Create `src/main/resources/db/migration/V15__care_team_memberships.sql`.
- Create `src/main/kotlin/dev/ehr/careteam/`: `CareTeamModels.kt`, `CareTeamRepository.kt`, `CareTeamService.kt`, `CareTeamController.kt`.
- Modify `PolicyModels.kt`/`PolicyEvaluator.kt` (`CARE_TEAM`, `policy-spine-v15` + literal updates), `EncounterService.kt` (derive membership on open).
- Create `src/test/kotlin/dev/ehr/careteam/CareTeamApiIntegrationTest.kt`.

## Acceptance Criteria

- Schema: org FK, composite same-org patient FK, `user_id` FK, constrained `role` (`attending`/`covering`/`care-team`) and `origin` (`explicit`/`encounter-derived`), `period_start` default now, nullable `period_end` (`> period_start` when set), partial unique active index per (org, patient, user, role), lookup index `(organization_id, user_id, patient_id) where period_end is null`.
- API: `POST /api/v1/patients/{id}/care-team` (add explicit member by user id + role → `201`; `404` cross-tenant patient; `409` duplicate active; `400` unknown user); `GET /api/v1/patients/{id}/care-team` (active members, `404` unknown patient); `POST /api/v1/care-team-memberships/{id}/end` (`200`, `422` already ended, `404` cross-tenant) — all audited on resource type `CARE_TEAM` with the patient compartment ID.
- Opening an encounter creates an `encounter-derived` `care-team` membership for the acting clinician inside the same transaction, exactly once even across repeated encounters.
- Policy: `CARE_TEAM` READ/WRITE for CLINICIAN + ORG_ADMIN, `user|system / CareTeam|* . read|write` scopes; staff denied; `policy-spine-v15`.
- Full suite green apart from policy-version literals.

## Intentional Deferrals

- No enforcement or shadow evaluation (H2/H3), no auto-expiry job (H3), no FHIR `CareTeam` (H4), no practitioner-role linkage beyond user id.

## Tasks

- [ ] Failing schema + API tests (matrix incl. encounter-derived idempotency and tenancy).
- [ ] Implement V15, careteam package, policy bump + literals, encounter-open hook.
- [ ] Focused + full suites; commit plan as `docs: add Slice H1 care team foundation plan`; implementation as `feat: add care team membership foundation`.

## Self-Review Checklist

- Memberships are tenant-isolated and compartment-keyed; ending preserves history.
- Encounter-derived creation is atomic with the encounter and idempotent.
- Nothing enforces yet — no behavior change for any existing endpoint beyond the new membership row.
