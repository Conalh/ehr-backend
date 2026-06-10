# Compartment Authorization Design

Date: 2026-06-10
Status: Draft for review — no code until this is agreed.

## Problem

Today any clinician in an organization can read any patient's chart there.
That was an explicit Slice 3 deferral (org + role + scope only). The design
spec's recommended default (open decision #4) is explicit patient assignment
for clinician chart access. This document decides how.

## What standards give us (and what they don't)

No standard mandates how a server decides care relationships — that is
organizational policy. We therefore make our own enforcement model but borrow
standard *shapes* so nothing is remodeled later:

- **FHIR `CareTeam`** shapes the relationship data; the table below can be
  served as a FHIR resource when needed.
- **FHIR Patient compartment** is what every clinical table already encodes
  (`patient_id` on every row); this design adds *who may enter* a compartment.
- **HL7 v3 `PurposeOfUse`** (`TREAT`, `ETREAT`) shapes break-glass; the
  `PolicyDecision.relationshipBasis` and `purposeOfUse` fields have been
  reserved for this since Slice 1.
- **HIPAA minimum-necessary** is the principle being implemented; we still
  claim no compliance (synthetic data only).

## Decision 1 — What grants a relationship

**Options:**
A. Explicit care-team membership only (clean, but every encounter needs
   upfront assignment — operationally heavy).
B. Encounter-derived only (treating someone grants access — automatic, but no
   way to grant access without an encounter, e.g. covering physician).
C. **Hybrid (recommended):** explicit care-team membership, plus an automatic
   membership created when a clinician opens an encounter for the patient.
   One table, two provenance paths.

## Decision 2 — Where enforcement lives

**Options:**
A. Per-service checks before each repository call (N copies, drift risk).
B. **Extend the policy spine (recommended):** `PolicyEvaluationRequest` gains
   an optional `patientId`; `PolicyEvaluator` consults a `CareTeamRepository`
   when the rule demands a relationship, and the decision's
   `relationshipBasis` records what satisfied it (`care-team-member`,
   `encounter-derived`, `break-glass`, or null). Services already construct
   the request and already know the patient — the call-site change is small
   and uniform, and audit evidence stays centralized.

A note on mechanics: the evaluator becomes repository-dependent (it is pure
today). That is acceptable — the lookup is one indexed query per decision —
but the evaluator stays synchronous and the lookup is injected as a narrow
`RelationshipResolver` interface so tests stay cheap.

## Decision 3 — Which rules require a relationship

Clinical-record reads and writes (CONDITION, ALLERGY, OBSERVATION, MEDICATION,
NOTE, ORDER, DIAGNOSTIC_REPORT, CHART, PROVENANCE) require it. PATIENT and
ENCOUNTER reads stay org-wide for CLINICIAN/STAFF (front-desk reality:
registration and scheduling precede relationships; opening an encounter is
how a relationship begins). Creating an encounter therefore stays org-wide
for clinicians and *establishes* the relationship (Decision 1C). Admin,
EXPORT, and OAUTH_CLIENT rules are unchanged (export is population-scale by
nature and already clinician+wildcard; revisit when system-apps exist).

## Decision 4 — Rollout posture

**Shadow first (recommended).** Phase 1 records relationships and evaluates
them, writing `relationshipBasis` (or its absence) into every audit row
without denying anything. Phase 2 flips enforcement per organization
(`organizations.compartment_enforcement` column: `off | shadow | enforced`,
default `shadow`). This avoids a breaking flag-day and produces data to verify
the model before it can hurt anyone.

## Decision 5 — Break-glass

`POST` clinical reads may carry an explicit break-glass assertion (header or
request field) with a mandatory free-text reason. Effect: access granted
without a relationship, decision recorded with `purposeOfUse = ETREAT`,
`relationshipBasis = break-glass`, and the reason in audit metadata; a
distinct audit operation makes these trivially reportable. Break-glass never
bypasses tenancy, roles, or scopes — only the relationship requirement.

## Schema sketch

```sql
create table care_team_memberships (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references organizations (id),
    patient_id uuid not null,
    user_id uuid not null references users (id),
    role text not null,                  -- 'attending','covering','care-team' (extensible)
    origin text not null,                -- 'explicit','encounter-derived'
    period_start timestamptz not null default now(),
    period_end timestamptz,
    created_by uuid references users (id),
    constraint ctm_patient_same_org_fk foreign key (organization_id, patient_id)
        references patients (organization_id, id),
    constraint ctm_active_unique unique (organization_id, patient_id, user_id, role)
);
-- lookup path used by every policy decision:
create index ctm_org_user_patient_idx
    on care_team_memberships (organization_id, user_id, patient_id)
    where period_end is null;
```

Relationship = an open-period row for (org, user, patient). Ending a
relationship sets `period_end` (no deletes; history is the audit story).
RLS, when it arrives, mirrors exactly this predicate as defense-in-depth.

## Micro-slice breakdown

- **H1:** `care_team_memberships` schema + repository + explicit-membership
  API (clinician/org-admin manage, audited) + encounter-derived membership on
  encounter open. No enforcement.
- **H2:** policy spine extension (`patientId` in requests, resolver,
  `relationshipBasis` recorded) in **shadow mode**; all services pass the
  compartment patient; audit rows show what enforcement *would* do.
- **H3:** per-org enforcement flag + denial path (`NO_TREATMENT_RELATIONSHIP`
  reason code) + break-glass; isolation tests prove the matrix.
- **H4 (later):** FHIR `CareTeam` read/search; RLS policies mirroring the
  predicate.

## Open questions for review

1. Should STAFF encounter reads also require a relationship in `enforced`
   mode, or is scheduling genuinely org-wide? (Recommended: org-wide.)
2. Encounter-derived membership lifetime: open-ended until explicitly ended,
   or auto-expire N days after the encounter finishes? (Recommended: auto-end
   30 days after encounter completion, configurable.)
3. Does PATIENT-resource read stay org-wide in enforced mode? (Recommended:
   yes — demographics for registration; the clinical record is what
   compartments protect.)
