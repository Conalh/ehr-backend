# Slice UC3 Remaining US Core Profiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Third US Core slice: sweep the remaining served resources —
attempt `us-core-condition-problems-health-concerns`,
`us-core-allergyintolerance`, `us-core-encounter`,
`us-core-documentreference`, `us-core-careteam`, and `us-core-provenance`,
keeping the UC1/UC2 contract: declared, stamped, and validated together, or
honestly not claimed at all.

**Decided-and-recorded:**

1. **Constant categories where they are true.** Conditions are the problem
   list → `condition-category#problem-list-item`. Notes are clinical notes
   → `us-core-documentreference-category#clinical-note`. Both constants
   reflect what the model actually produces, like DiagnosticReport's LAB.
2. **Practitioner is pre-recorded as base-R4-only.** `us-core-practitioner`
   requires `name.family 1..1`; practitioners carry only an unstructured
   display name. Splitting a display string into family/given would be
   fabrication. Like MedicationStatement, the gap report says so explicitly
   (UC5 refresh).
3. **CareTeam stamps conditionally.** `us-core-careteam` requires
   `participant 1..*`; a patient's team with no active members is valid in
   this model, so only participant-bearing instances stamp the profile —
   the same instance-satisfies-what-it-claims principle as Observation's
   category-matched stamp.
4. **Empirical triage rules.** Each attempted profile is kept only if the
   validator passes with no error-severity findings; structural
   requirements we cannot honestly meet (e.g. required bindings to VSAC
   sets our local role codes are outside of, reference-target constraints
   on identifier-only references) demote that resource to
   not-claimed-and-recorded rather than fudged.

---

## File Structure

- Modify the six mappers (profile stamps; Condition + DocumentReference categories), `FhirCapabilityRegistry.kt` (profiles for whatever survives triage), `FhirConformanceValidationTest.kt` (claim assertions), `CapabilityStatementIntegrationTest.kt`.

## Acceptance Criteria

- Every surviving profile validates with no error-severity findings; every demoted one is listed for the UC5 gap-report refresh with its concrete reason.
- Instances only ever stamp profiles they satisfy (CareTeam's conditional stamp tested both ways).
- Registry/CapabilityStatement/mapper lockstep maintained; full suite green.

## Intentional Deferrals

- Practitioner structured names (would need schema + API changes — record, revisit only if an Inferno run makes it decisive); search parameters (UC4); `_revinclude` + gap-report refresh (UC5).

## Tasks

- [ ] Stamps + categories across the six mappers; registry entries.
- [ ] Run conformance; triage each finding: fix honestly or demote-and-record.
- [ ] Conformance + capability assertions for survivors; focused + full suites; commit plan as `docs: add Slice UC3 remaining profiles plan`; implementation as `feat: declare and validate the remaining US Core profiles`.

## Self-Review Checklist

- Nothing fudged to pass: every kept profile is genuinely satisfied by every instance that stamps it.
- Every demotion has a written reason traceable to a validator finding.
